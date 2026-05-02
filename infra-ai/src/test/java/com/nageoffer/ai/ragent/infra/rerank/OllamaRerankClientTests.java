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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaRerankClientTests {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void rerankScoresCandidatesThroughOllamaGenerate() throws Exception {
        List<String> requestBodies = new ArrayList<>();
        startServer(requestBodies);

        OllamaRerankClient client = new OllamaRerankClient(new OkHttpClient());
        List<RetrievedChunk> reranked = client.rerank(
                "beach dog",
                List.of(
                        new RetrievedChunk("low", "database transaction notes", 0.2f),
                        new RetrievedChunk("high", "golden retriever playing on a beach", 0.4f)
                ),
                1,
                target()
        );

        assertEquals(1, reranked.size());
        assertEquals("high", reranked.get(0).getId());
        assertEquals(0.92f, reranked.get(0).getScore());
        assertEquals(2, requestBodies.size());

        JsonObject body = JsonParser.parseString(requestBodies.get(0)).getAsJsonObject();
        assertEquals("MedAIBase/Qwen3-VL-Reranker:2b", body.get("model").getAsString());
        assertEquals("json", body.get("format").getAsString());
        assertEquals("30s", body.get("keep_alive").getAsString());
        assertTrue(body.get("prompt").getAsString().contains("beach dog"));
    }

    private ModelTarget target() {
        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        provider.setEndpoints(java.util.Map.of("rerank", "/api/generate"));
        provider.setKeepAlive("30s");

        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setId("qwen3-vl-rerank-local");
        candidate.setProvider("ollama");
        candidate.setModel("MedAIBase/Qwen3-VL-Reranker:2b");

        return new ModelTarget(candidate.getId(), candidate, provider);
    }

    private void startServer(List<String> requestBodies) throws IOException {
        AtomicInteger callCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/generate", exchange -> handle(exchange, callCount, requestBodies));
        server.start();
    }

    private void handle(HttpExchange exchange,
                        AtomicInteger callCount,
                        List<String> requestBodies) throws IOException {
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requestBodies.add(requestBody);
        int call = callCount.incrementAndGet();
        String score = call == 1 ? "0.12" : "0.92";
        String response = "{\"response\":\"{\\\"score\\\":" + score + "}\"}";
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
