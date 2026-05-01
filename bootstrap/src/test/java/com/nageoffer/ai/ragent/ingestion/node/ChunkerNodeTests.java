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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.core.chunk.ChunkingMode;
import com.nageoffer.ai.ragent.core.chunk.ChunkingOptions;
import com.nageoffer.ai.ragent.core.chunk.ChunkingStrategy;
import com.nageoffer.ai.ragent.core.chunk.ChunkingStrategyFactory;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.context.StructuredDocument;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChunkerNodeTests {

    @Test
    void shouldBuildVisualChunksWithoutTextChunks() {
        ObjectMapper objectMapper = new ObjectMapper();
        ChunkingStrategyFactory chunkingStrategyFactory = mock(ChunkingStrategyFactory.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(embeddingService.embedBatch(anyList(), eq("qwen3-vl-embedding-1024")))
                .thenReturn(List.of(List.of(0.1f, 0.2f, 0.3f)));

        ChunkerNode node = new ChunkerNode(objectMapper, chunkingStrategyFactory, embeddingService);
        ObjectNode settings = objectMapper.createObjectNode();
        settings.put("visualEmbeddingModel", "qwen3-vl-embedding-1024");
        settings.put("visualMaxLength", 2000);

        IngestionContext context = IngestionContext.builder()
                .document(StructuredDocument.builder()
                        .visualBlocks(List.of(StructuredDocument.VisualBlock.builder()
                                .blockId("image-block-1")
                                .blockType("image")
                                .imageUri("s3://kb/image.jpg")
                                .summary("A product diagram")
                                .build()))
                        .build())
                .build();

        NodeResult result = node.execute(context, NodeConfig.builder()
                .nodeId("chunker")
                .nodeType("chunker")
                .settings(settings)
                .build());

        Assertions.assertTrue(result.isSuccess());
        Assertions.assertNotNull(context.getChunks());
        Assertions.assertTrue(context.getChunks().isEmpty());
        Assertions.assertEquals(1, context.getVisualChunks().size());
        Assertions.assertEquals("image-block-1", context.getVisualChunks().get(0).getChunkId());
    }

    @Test
    void shouldPassContextEmbeddingModelToTextChunker() {
        ObjectMapper objectMapper = new ObjectMapper();
        ChunkingStrategyFactory chunkingStrategyFactory = mock(ChunkingStrategyFactory.class);
        ChunkingStrategy chunkingStrategy = mock(ChunkingStrategy.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(chunkingStrategyFactory.requireStrategy(ChunkingMode.STRUCTURE_AWARE))
                .thenReturn(chunkingStrategy);
        when(chunkingStrategy.chunk(eq("ocr text"), org.mockito.ArgumentMatchers.any(ChunkingOptions.class)))
                .thenAnswer(invocation -> {
                    ChunkingOptions options = invocation.getArgument(1);
                    Assertions.assertEquals(
                            "qwen3-vl-embedding-1024",
                            options.getMetadata("embeddingModel", null)
                    );
                    return List.of(VectorChunk.builder()
                            .chunkId("text-1")
                            .index(0)
                            .content("ocr text")
                            .embedding(new float[]{0.1f, 0.2f, 0.3f})
                            .build());
                });

        ChunkerNode node = new ChunkerNode(objectMapper, chunkingStrategyFactory, embeddingService);
        ObjectNode settings = objectMapper.createObjectNode();
        settings.put("strategy", "structure_aware");

        IngestionContext context = IngestionContext.builder()
                .rawText("ocr text")
                .metadata(Map.of("embeddingModel", "qwen3-vl-embedding-1024"))
                .build();

        NodeResult result = node.execute(context, NodeConfig.builder()
                .nodeId("chunker")
                .nodeType("chunker")
                .settings(settings)
                .build());

        Assertions.assertTrue(result.isSuccess());
        Assertions.assertEquals(1, context.getChunks().size());
    }
}
