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

package com.nageoffer.ai.ragent.infra.rerank;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
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
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocalHfRerankClient implements RerankClient {

    private final Gson gson = new Gson();
    private final OkHttpClient httpClient;

    @Override
    public String provider() {
        return ModelProvider.LOCAL_HF.getId();
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<RetrievedChunk> dedup = deduplicate(candidates);
        if (topN <= 0 || dedup.size() <= topN) {
            return dedup;
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", requireModel(target));
        body.addProperty("query", query);
        body.addProperty("top_n", topN);

        JsonArray documents = new JsonArray();
        for (RetrievedChunk candidate : dedup) {
            documents.add(candidate.getText() == null ? "" : candidate.getText());
        }
        body.add("documents", documents);

        JsonObject response = executeRequest(resolveUrl(requireProvider(target), target), body);
        List<ScoredChunk> scored = readResults(response, dedup);

        if (scored.isEmpty()) {
            throw new ModelClientException("Local HF rerank response has no valid results",
                    ModelClientErrorType.INVALID_RESPONSE, null);
        }

        return scored.stream()
                .sorted(Comparator.comparing(ScoredChunk::score).reversed())
                .limit(topN)
                .map(item -> withScore(item.chunk(), item.score()))
                .toList();
    }

    private JsonObject executeRequest(String url, JsonObject body) {
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), HttpMediaTypes.JSON))
                .addHeader("Content-Type", HttpMediaTypes.JSON_UTF8_HEADER)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errBody = readBody(response.body());
                log.warn("Local HF rerank HTTP error: status={}, body={}", response.code(), errBody);
                throw new ModelClientException(
                        "Local HF rerank failed: HTTP " + response.code() + " - " + errBody,
                        classifyStatus(response.code()),
                        response.code()
                );
            }
            return parseJsonBody(response.body());
        } catch (IOException e) {
            throw new ModelClientException(
                    "Local HF rerank failed: " + e.getMessage(),
                    ModelClientErrorType.NETWORK_ERROR,
                    null,
                    e
            );
        }
    }

    private List<ScoredChunk> readResults(JsonObject root, List<RetrievedChunk> candidates) {
        if (root == null) {
            throw new ModelClientException("Local HF rerank response is empty", ModelClientErrorType.INVALID_RESPONSE, null);
        }

        JsonArray results = root.getAsJsonArray("results");
        if (results == null && root.has("scores")) {
            results = buildResultsFromScores(root.getAsJsonArray("scores"));
        }
        if (results == null || results.isEmpty()) {
            throw new ModelClientException("Local HF rerank response missing results",
                    ModelClientErrorType.INVALID_RESPONSE, null);
        }

        List<ScoredChunk> scored = new ArrayList<>();
        for (JsonElement element : results) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            if (!item.has("index") || !item.has("score")) {
                continue;
            }
            int index = item.get("index").getAsInt();
            if (index < 0 || index >= candidates.size()) {
                continue;
            }
            scored.add(new ScoredChunk(candidates.get(index), item.get("score").getAsFloat()));
        }
        return scored;
    }

    private JsonArray buildResultsFromScores(JsonArray scores) {
        JsonArray results = new JsonArray();
        if (scores == null) {
            return results;
        }
        for (int i = 0; i < scores.size(); i++) {
            JsonObject item = new JsonObject();
            item.addProperty("index", i);
            item.add("score", scores.get(i));
            results.add(item);
        }
        return results;
    }

    private List<RetrievedChunk> deduplicate(List<RetrievedChunk> candidates) {
        List<RetrievedChunk> dedup = new ArrayList<>(candidates.size());
        Set<String> seen = new HashSet<>();
        for (RetrievedChunk candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            if (seen.add(candidate.getId())) {
                dedup.add(candidate);
            }
        }
        return dedup;
    }

    private RetrievedChunk withScore(RetrievedChunk source, float score) {
        return new RetrievedChunk(source.getId(), source.getText(), score, source.getMetadata());
    }

    private AIModelProperties.ProviderConfig requireProvider(ModelTarget target) {
        if (target == null || target.provider() == null) {
            throw new IllegalStateException("Local HF rerank provider config is missing");
        }
        return target.provider();
    }

    private String requireModel(ModelTarget target) {
        if (target == null || target.candidate() == null || !StringUtils.hasText(target.candidate().getModel())) {
            throw new IllegalStateException("Local HF rerank model name is missing");
        }
        return target.candidate().getModel();
    }

    private String resolveUrl(AIModelProperties.ProviderConfig provider, ModelTarget target) {
        return ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.RERANK);
    }

    private JsonObject parseJsonBody(ResponseBody body) throws IOException {
        if (body == null) {
            throw new ModelClientException("Local HF rerank response is empty", ModelClientErrorType.INVALID_RESPONSE, null);
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

    private record ScoredChunk(RetrievedChunk chunk, float score) {
    }
}
