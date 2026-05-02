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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalHfRerankClientTests {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void rerankPostsQueryAndDocumentsToLocalHfBridge() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer("""
                {"results":[
                  {"index":1,"score":0.93},
                  {"index":0,"score":0.12}
                ]}
                """, requestBody);

        LocalHfRerankClient client = new LocalHfRerankClient(new OkHttpClient());
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
        assertEquals(0.93f, reranked.get(0).getScore());

        JsonObject body = JsonParser.parseString(requestBody.get()).getAsJsonObject();
        assertEquals("Qwen/Qwen3-Reranker-0.6B", body.get("model").getAsString());
        assertEquals("beach dog", body.get("query").getAsString());
        assertEquals("database transaction notes", body.getAsJsonArray("documents").get(0).getAsString());
        assertEquals(1, body.get("top_n").getAsInt());
    }

    @Test
    void providerIsLocalHf() {
        LocalHfRerankClient client = new LocalHfRerankClient(new OkHttpClient());

        assertEquals("local-hf", client.provider());
    }

    private ModelTarget target() {
        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        provider.setEndpoints(java.util.Map.of("rerank", "/v1/rerank"));

        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setId("qwen3-rerank-hf-small");
        candidate.setProvider("local-hf");
        candidate.setModel("Qwen/Qwen3-Reranker-0.6B");

        return new ModelTarget(candidate.getId(), candidate, provider);
    }

    private void startServer(String response, AtomicReference<String> requestBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/rerank", exchange -> handle(exchange, response, requestBody));
        server.start();
    }

    private void handle(HttpExchange exchange, String response, AtomicReference<String> requestBody)
            throws IOException {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
