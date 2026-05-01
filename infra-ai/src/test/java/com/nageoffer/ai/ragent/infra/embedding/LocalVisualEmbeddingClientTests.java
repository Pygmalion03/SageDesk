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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalVisualEmbeddingClientTests {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void embedBatchPostsModelInputAndDimensionsToLocalBridge() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(200, "{\"embeddings\":[[0.1,0.2],[0.3,0.4]]}", requestBody);

        LocalVisualEmbeddingClient client = new LocalVisualEmbeddingClient(new OkHttpClient());
        List<List<Float>> vectors = client.embedBatch(List.of("alpha", "beta"), target());

        assertEquals(List.of(0.1f, 0.2f), vectors.get(0));
        assertEquals(List.of(0.3f, 0.4f), vectors.get(1));

        JsonObject body = JsonParser.parseString(requestBody.get()).getAsJsonObject();
        assertEquals("Qwen/Qwen3-VL-Embedding-2B", body.get("model").getAsString());
        assertEquals(1024, body.get("dimensions").getAsInt());
        assertEquals("alpha", body.getAsJsonArray("input").get(0).getAsString());
        assertEquals("beta", body.getAsJsonArray("input").get(1).getAsString());
    }

    @Test
    void embedBatchParsesOpenAICompatibleDataEmbeddingResponse() throws Exception {
        startServer(200, "{\"data\":[{\"embedding\":[1.0,2.0]},{\"embedding\":[3.0,4.0]}]}", new AtomicReference<>());

        LocalVisualEmbeddingClient client = new LocalVisualEmbeddingClient(new OkHttpClient());
        List<List<Float>> vectors = client.embedBatch(List.of("alpha", "beta"), target());

        assertEquals(List.of(1.0f, 2.0f), vectors.get(0));
        assertEquals(List.of(3.0f, 4.0f), vectors.get(1));
    }

    @Test
    void providerIsLocalVisualEmbedding() {
        LocalVisualEmbeddingClient client = new LocalVisualEmbeddingClient(new OkHttpClient());

        assertEquals("local-vl", client.provider());
    }

    private ModelTarget target() {
        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        provider.setEndpoints(java.util.Map.of("embedding", "/v1/embeddings"));

        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setId("qwen3-vl-embedding-2b-local");
        candidate.setProvider("local-vl");
        candidate.setModel("Qwen/Qwen3-VL-Embedding-2B");
        candidate.setDimension(1024);

        return new ModelTarget(candidate.getId(), candidate, provider);
    }

    private void startServer(int status, String response, AtomicReference<String> requestBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> handle(exchange, status, response, requestBody));
        server.start();
    }

    private void handle(HttpExchange exchange, int status, String response, AtomicReference<String> requestBody)
            throws IOException {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
