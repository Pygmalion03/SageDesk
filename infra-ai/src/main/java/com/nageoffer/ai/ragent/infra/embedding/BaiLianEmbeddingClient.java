/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.infra.embedding;

import cn.hutool.core.collection.CollUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.http.HttpMediaTypes;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.http.ModelUrlResolver;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class BaiLianEmbeddingClient implements EmbeddingClient {

    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();

    @Override
    public String provider() {
        return ModelProvider.BAI_LIAN.getId();
    }

    @Override
    public List<Float> embed(String text, ModelTarget target) {
        return embedBatch(List.of(text), target).get(0);
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts, ModelTarget target) {
        if (CollUtil.isEmpty(texts)) {
            return Collections.emptyList();
        }

        final int maxBatch = isMultimodalModel(target) ? 20 : 32;
        List<List<Float>> results = new ArrayList<>(Collections.nCopies(texts.size(), null));
        for (int i = 0, n = texts.size(); i < n; i += maxBatch) {
            int end = Math.min(i + maxBatch, n);
            List<String> slice = texts.subList(i, end);
            List<List<Float>> part = isMultimodalModel(target)
                    ? doMultimodalEmbed(slice, target)
                    : doCompatibleEmbed(slice, target);
            for (int k = 0; k < part.size(); k++) {
                results.set(i + k, part.get(k));
            }
        }

        for (int i = 0; i < results.size(); i++) {
            if (results.get(i) == null) {
                throw new ModelClientException("Embedding result missing, index=" + i, ModelClientErrorType.INVALID_RESPONSE, null);
            }
        }
        return results;
    }

    private List<List<Float>> doCompatibleEmbed(List<String> texts, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = requireProvider(target);
        Map<String, Object> req = new HashMap<>();
        req.put("model", requireModel(target));
        req.put("input", texts);
        if (target.candidate().getDimension() != null) {
            req.put("dimensions", target.candidate().getDimension());
        }
        req.put("encoding_format", "float");
        JsonObject root = executeRequest(resolveUrl(provider, target), req, provider.getApiKey(), "BaiLian embedding");
        JsonArray data = root.getAsJsonArray("data");
        if (data == null) {
            throw new ModelClientException("BaiLian embedding response missing data", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        List<List<Float>> vectors = new ArrayList<>(data.size());
        for (JsonElement el : data) {
            JsonArray emb = el.getAsJsonObject().getAsJsonArray("embedding");
            if (emb == null) {
                throw new ModelClientException("BaiLian embedding response missing embedding", ModelClientErrorType.INVALID_RESPONSE, null);
            }
            vectors.add(readEmbeddingArray(emb));
        }
        return vectors;
    }

    private List<List<Float>> doMultimodalEmbed(List<String> texts, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = requireProvider(target);
        Map<String, Object> req = new HashMap<>();
        req.put("model", requireModel(target));
        List<Map<String, Object>> contents = texts.stream()
                .map(text -> Map.<String, Object>of("text", text))
                .toList();
        req.put("input", Map.of("contents", contents));
        if (target.candidate().getDimension() != null) {
            req.put("parameters", Map.of("dimension", target.candidate().getDimension()));
        }

        JsonObject root = executeRequest(resolveUrl(provider, target), req, provider.getApiKey(), "BaiLian multimodal embedding");
        JsonObject output = root.getAsJsonObject("output");
        if (output == null || !output.has("embeddings")) {
            throw new ModelClientException("BaiLian multimodal embedding response missing output.embeddings", ModelClientErrorType.INVALID_RESPONSE, null);
        }

        JsonArray embeddings = output.getAsJsonArray("embeddings");
        List<List<Float>> vectors = new ArrayList<>(embeddings.size());
        for (JsonElement element : embeddings) {
            JsonArray emb = element.getAsJsonObject().getAsJsonArray("embedding");
            if (emb == null) {
                throw new ModelClientException("BaiLian multimodal embedding response missing embedding", ModelClientErrorType.INVALID_RESPONSE, null);
            }
            vectors.add(readEmbeddingArray(emb));
        }
        return vectors;
    }

    private JsonObject executeRequest(String url, Map<String, Object> reqBody, String apiKey, String actionName) {
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(gson.toJson(reqBody), HttpMediaTypes.JSON))
                .addHeader("Content-Type", HttpMediaTypes.JSON_UTF8_HEADER)
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errBody = readBody(response.body());
                log.error("{} HTTP error: status={}, body={}", actionName, response.code(), errBody);
                throw new ModelClientException(
                        actionName + " failed: HTTP " + response.code() + " - " + errBody,
                        classifyStatus(response.code()),
                        response.code()
                );
            }
            return parseJsonBody(response.body());
        } catch (IOException e) {
            throw new ModelClientException(actionName + " failed: " + e.getMessage(), ModelClientErrorType.NETWORK_ERROR, null, e);
        }
    }

    private List<Float> readEmbeddingArray(JsonArray emb) {
        List<Float> vector = new ArrayList<>(emb.size());
        for (JsonElement num : emb) {
            vector.add(num.getAsFloat());
        }
        return vector;
    }

    private boolean isMultimodalModel(ModelTarget target) {
        String model = requireModel(target);
        return model.contains("vl-embedding")
                || model.contains("multimodal-embedding")
                || model.contains("embedding-vision");
    }

    private AIModelProperties.ProviderConfig requireProvider(ModelTarget target) {
        if (target == null || target.provider() == null) {
            throw new IllegalStateException("BaiLian provider config is missing");
        }
        if (target.provider().getApiKey() == null || target.provider().getApiKey().isBlank()) {
            throw new IllegalStateException("BaiLian API key is missing");
        }
        return target.provider();
    }

    private String requireModel(ModelTarget target) {
        if (target == null || target.candidate() == null || target.candidate().getModel() == null) {
            throw new IllegalStateException("BaiLian model name is missing");
        }
        return target.candidate().getModel();
    }

    private String resolveUrl(AIModelProperties.ProviderConfig provider, ModelTarget target) {
        return ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.EMBEDDING);
    }

    private JsonObject parseJsonBody(ResponseBody body) throws IOException {
        if (body == null) {
            throw new ModelClientException("BaiLian embedding response is empty", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        String content = body.string();
        return JsonParser.parseString(content).getAsJsonObject();
    }

    private String readBody(ResponseBody body) throws IOException {
        if (body == null) {
            return "";
        }
        return new String(body.bytes(), StandardCharsets.UTF_8);
    }

    private ModelClientErrorType classifyStatus(int status) {
        if (status == 401 || status == 403) {
            return ModelClientErrorType.UNAUTHORIZED;
        }
        if (status == 429) {
            return ModelClientErrorType.RATE_LIMITED;
        }
        if (status >= 500) {
            return ModelClientErrorType.SERVER_ERROR;
        }
        return ModelClientErrorType.CLIENT_ERROR;
    }
}
