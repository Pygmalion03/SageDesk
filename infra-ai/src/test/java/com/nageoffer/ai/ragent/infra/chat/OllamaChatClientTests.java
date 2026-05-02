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

package com.nageoffer.ai.ragent.infra.chat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
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

class OllamaChatClientTests {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void chatPostsMainLocalModelAndKeepAlive() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer("{\"message\":{\"role\":\"assistant\",\"content\":\"OK\"}}", requestBody);

        OllamaChatClient client = new OllamaChatClient(new OkHttpClient(), Runnable::run);
        String response = client.chat(ChatRequest.builder()
                .messages(List.of(ChatMessage.user("ping")))
                .build(), target());

        assertEquals("OK", response);
        JsonObject body = JsonParser.parseString(requestBody.get()).getAsJsonObject();
        assertEquals("qwen3.5:9b", body.get("model").getAsString());
        assertEquals("30s", body.get("keep_alive").getAsString());
        assertEquals("user", body.getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString());
    }

    private ModelTarget target() {
        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        provider.setEndpoints(java.util.Map.of("chat", "/api/chat"));
        provider.setKeepAlive("30s");

        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setId("qwen3-5-local-main");
        candidate.setProvider("ollama");
        candidate.setModel("qwen3.5:9b");

        return new ModelTarget(candidate.getId(), candidate, provider);
    }

    private void startServer(String response, AtomicReference<String> requestBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> handle(exchange, response, requestBody));
        server.start();
    }

    private void handle(HttpExchange exchange, String response, AtomicReference<String> requestBody) throws IOException {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
