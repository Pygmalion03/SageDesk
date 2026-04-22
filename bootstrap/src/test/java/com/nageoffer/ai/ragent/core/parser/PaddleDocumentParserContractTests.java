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

package com.nageoffer.ai.ragent.core.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.rag.config.DocumentAnalysisProperties;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;

class PaddleDocumentParserContractTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldSendExpectedPayloadAndParseStructuredResponse() throws Exception {
        AtomicReference<String> authorizationRef = new AtomicReference<>();
        AtomicReference<JsonNode> requestBodyRef = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/document-analysis", exchange -> {
            authorizationRef.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBodyRef.set(objectMapper.readTree(exchange.getRequestBody()));
            writeJson(exchange, """
                    {
                      "text": "OCR output",
                      "metadata": {
                        "engine": "pp_structure_v3"
                      },
                      "document": {
                        "text": "OCR output",
                        "visualBlocks": [
                          {
                            "blockId": "img-1",
                            "blockType": "chart",
                            "pageNo": 2,
                            "imageUri": "https://example.com/chart.png",
                            "summary": "Revenue chart"
                          }
                        ]
                      }
                    }
                    """);
        });
        server.start();

        DocumentAnalysisProperties properties = new DocumentAnalysisProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setEndpoint("/v1/document-analysis");
        properties.setApiKey("bridge-token");
        properties.setDefaultMode("pp_structure_v3");
        properties.setFallbackMode("paddleocr_vl_1_5");

        PaddleDocumentParser parser = new PaddleDocumentParser(
                new OkHttpClient(),
                objectMapper,
                properties,
                mock(FileStorageService.class),
                mock(S3Client.class)
        );
        byte[] content = "fake-pdf-binary".getBytes(StandardCharsets.UTF_8);

        ParseResult result = parser.parse(content, "application/pdf", Map.of("pageLimit", 3));

        Assertions.assertEquals("Bearer bridge-token", authorizationRef.get());
        Assertions.assertNotNull(requestBodyRef.get());
        Assertions.assertEquals("application/pdf", requestBodyRef.get().path("mimeType").asText());
        Assertions.assertEquals("pp_structure_v3", requestBodyRef.get().path("mode").asText());
        Assertions.assertEquals("paddleocr_vl_1_5", requestBodyRef.get().path("fallbackMode").asText());
        Assertions.assertEquals(Base64.getEncoder().encodeToString(content),
                requestBodyRef.get().path("contentBase64").asText());
        Assertions.assertEquals(3, requestBodyRef.get().path("options").path("pageLimit").asInt());

        Assertions.assertEquals("OCR output", result.text());
        Assertions.assertEquals("pp_structure_v3", result.metadata().get("engine"));
        Assertions.assertNotNull(result.document());
        Assertions.assertNotNull(result.document().getVisualBlocks());
        Assertions.assertEquals(1, result.document().getVisualBlocks().size());
        Assertions.assertEquals("https://example.com/chart.png",
                result.document().getVisualBlocks().get(0).getImageUri());
    }

    @Test
    void shouldSupportPdfAndImageMimeTypes() {
        DocumentAnalysisProperties properties = new DocumentAnalysisProperties();
        properties.setEnabled(true);
        PaddleDocumentParser parser = new PaddleDocumentParser(
                new OkHttpClient(),
                objectMapper,
                properties,
                mock(FileStorageService.class),
                mock(S3Client.class)
        );

        Assertions.assertTrue(parser.supports("application/pdf"));
        Assertions.assertTrue(parser.supports("image/png"));
        Assertions.assertFalse(parser.supports("text/plain"));
    }

    private void writeJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }
}
