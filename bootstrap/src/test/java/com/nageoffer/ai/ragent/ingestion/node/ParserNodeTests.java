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

package com.nageoffer.ai.ragent.ingestion.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.core.parser.DocumentParser;
import com.nageoffer.ai.ragent.core.parser.DocumentParserSelector;
import com.nageoffer.ai.ragent.core.parser.ParseResult;
import com.nageoffer.ai.ragent.core.parser.ParserType;
import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.SourceType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.rag.config.DocumentAnalysisProperties;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class ParserNodeTests {

    @Test
    void shouldNotFallbackToTikaWhenImagePaddleParsingFails() {
        ObjectMapper objectMapper = new ObjectMapper();
        DocumentAnalysisProperties documentAnalysisProperties = new DocumentAnalysisProperties();
        documentAnalysisProperties.setFallbackToTikaOnError(true);
        RAGDefaultProperties ragDefaultProperties = new RAGDefaultProperties();
        ragDefaultProperties.setCollectionName("default_collection");

        DocumentParser paddle = new DocumentParser() {
            @Override
            public String getParserType() {
                return ParserType.PADDLE_DOCUMENT_ANALYSIS.getType();
            }

            @Override
            public ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
                throw new IllegalStateException("paddle unavailable");
            }
        };
        DocumentParser tika = new DocumentParser() {
            @Override
            public String getParserType() {
                return ParserType.TIKA.getType();
            }

            @Override
            public ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
                return ParseResult.ofText("fallback text");
            }
        };

        ParserNode node = new ParserNode(
                objectMapper,
                new DocumentParserSelector(List.of(paddle, tika)),
                documentAnalysisProperties,
                ragDefaultProperties
        );

        NodeResult result = node.execute(IngestionContext.builder()
                .rawBytes(new byte[] {1, 2, 3})
                .mimeType("image/jpeg")
                .source(DocumentSource.builder()
                        .type(SourceType.FILE)
                        .fileName("photo.jpg")
                        .build())
                .build(), NodeConfig.builder()
                .nodeId("parser")
                .nodeType("parser")
                .settings(parserRules(objectMapper, "IMAGE"))
                .build());

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("paddle unavailable", result.getMessage());
    }

    private ObjectNode parserRules(ObjectMapper objectMapper, String mimeType) {
        ObjectNode settings = objectMapper.createObjectNode();
        ArrayNode rules = settings.putArray("rules");
        ObjectNode imageRule = rules.addObject();
        imageRule.put("mimeType", mimeType);
        imageRule.put("parserType", "PaddleDocumentAnalysis");
        ObjectNode fallbackRule = rules.addObject();
        fallbackRule.put("mimeType", "ALL");
        fallbackRule.put("parserType", "Tika");
        return settings;
    }
}
