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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OllamaRerankClient implements RerankClient {

    private static final Pattern SCORE_PATTERN = Pattern.compile("(?i)score[\"'\\s:=>-]*([01](?:\\.\\d+)?)");

    private final Gson gson = new Gson();
    private final OkHttpClient httpClient;

    @Override
    public String provider() {
        return ModelProvider.OLLAMA.getId();
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

        List<ScoredChunk> scored = new ArrayList<>(dedup.size());
        for (RetrievedChunk candidate : dedup) {
            float score = scoreCandidate(query, candidate, target);
            scored.add(new ScoredChunk(candidate, score));
        }

        return scored.stream()
                .sorted(Comparator.comparing(ScoredChunk::score).reversed())
                .limit(topN)
                .map(item -> withScore(item.chunk(), item.score()))
                .collect(Collectors.toList());
    }

    private float scoreCandidate(String query, RetrievedChunk candidate, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = requireProvider(target);
        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("model", requireModel(target));
        reqBody.addProperty("prompt", buildPrompt(query, candidate.getText()));
        reqBody.addProperty("stream", false);
        reqBody.addProperty("format", "json");

        JsonObject options = new JsonObject();
        options.addProperty("temperature", 0);
        options.addProperty("num_predict", 32);
        reqBody.add("options", options);

        if (StringUtils.hasText(provider.getKeepAlive())) {
            reqBody.addProperty("keep_alive", provider.getKeepAlive());
        }

        Request request = new Request.Builder()
                .url(resolveUrl(provider, target))
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .addHeader("Content-Type", HttpMediaTypes.JSON_UTF8_HEADER)
                .build();

        JsonObject responseJson;
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = readBody(response.body());
                log.warn("Ollama rerank request failed: status={}, body={}", response.code(), body);
                throw new ModelClientException(
                        "Ollama rerank request failed: HTTP " + response.code(),
                        classifyStatus(response.code()),
                        response.code()
                );
            }
            responseJson = parseJsonBody(response.body());
        } catch (IOException e) {
            throw new ModelClientException("Ollama rerank request failed: " + e.getMessage(),
                    ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        return clampScore(readScore(responseJson));
    }

    private String buildPrompt(String query, String document) {
        return """
                You are a reranker. Score how relevant the document is to the query.
                Return only valid JSON in this exact shape: {"score": 0.0}
                The score must be between 0.0 and 1.0.

                Query:
                %s

                Document:
                %s
                """.formatted(nullToEmpty(query), nullToEmpty(document));
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

    private float readScore(JsonObject responseJson) {
        if (responseJson == null || !responseJson.has("response") || responseJson.get("response").isJsonNull()) {
            throw new ModelClientException("Ollama rerank response missing response",
                    ModelClientErrorType.INVALID_RESPONSE, null);
        }

        String response = responseJson.get("response").getAsString();
        try {
            JsonObject payload = JsonParser.parseString(response).getAsJsonObject();
            if (payload.has("score") && !payload.get("score").isJsonNull()) {
                return payload.get("score").getAsFloat();
            }
        } catch (JsonSyntaxException | IllegalStateException ignored) {
            // Some Ollama models ignore JSON mode. Fall through to a small regex parser.
        }

        Matcher matcher = SCORE_PATTERN.matcher(response);
        if (matcher.find()) {
            return Float.parseFloat(matcher.group(1));
        }

        throw new ModelClientException("Ollama rerank response missing score",
                ModelClientErrorType.INVALID_RESPONSE, null);
    }

    private float clampScore(float score) {
        if (score < 0f) {
            return 0f;
        }
        if (score > 1f) {
            return 1f;
        }
        return score;
    }

    private AIModelProperties.ProviderConfig requireProvider(ModelTarget target) {
        if (target == null || target.provider() == null) {
            throw new IllegalStateException("Ollama rerank provider config is missing");
        }
        return target.provider();
    }

    private String requireModel(ModelTarget target) {
        if (target == null || target.candidate() == null || !StringUtils.hasText(target.candidate().getModel())) {
            throw new IllegalStateException("Ollama rerank model name is missing");
        }
        return target.candidate().getModel();
    }

    private String resolveUrl(AIModelProperties.ProviderConfig provider, ModelTarget target) {
        return ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.RERANK);
    }

    private JsonObject parseJsonBody(ResponseBody body) throws IOException {
        if (body == null) {
            throw new ModelClientException("Ollama rerank response is empty", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        String content = body.string();
        return gson.fromJson(content, JsonObject.class);
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

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record ScoredChunk(RetrievedChunk chunk, float score) {
    }
}
