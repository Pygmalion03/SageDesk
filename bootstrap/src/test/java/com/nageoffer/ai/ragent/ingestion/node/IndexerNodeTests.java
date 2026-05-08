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
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceId;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.response.InsertResp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexerNodeTests {

    @Test
    void shouldIndexTextChunksUsingActualEmbeddingDimension() {
        VectorStoreAdmin vectorStoreAdmin = mock(VectorStoreAdmin.class);
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        InsertResp insertResp = mock(InsertResp.class);
        when(vectorStoreAdmin.vectorSpaceExists(any())).thenReturn(true);
        when(milvusClient.insert(any())).thenReturn(insertResp);
        when(insertResp.getInsertCnt()).thenReturn(1L);

        RAGDefaultProperties properties = new RAGDefaultProperties();
        properties.setDimension(1024);
        properties.setMetricType("COSINE");

        IndexerNode node = new IndexerNode(
                new ObjectMapper(),
                vectorStoreAdmin,
                milvusClient,
                properties
        );

        IngestionContext context = IngestionContext.builder()
                .taskId("doc-1")
                .pipelineId("pipeline-1")
                .metadata(Map.of("embeddingModel", "qwen-emb-8b"))
                .vectorSpaceId(VectorSpaceId.builder().logicalName("kb_collection").build())
                .chunks(List.of(VectorChunk.builder()
                        .chunkId("chunk-1")
                        .index(0)
                        .content("Ragent AI uses multi-channel retrieval.")
                        .embedding(new float[4096])
                        .build()))
                .build();

        NodeResult result = node.execute(context, NodeConfig.builder()
                .nodeId("indexer")
                .nodeType("indexer")
                .build());

        Assertions.assertTrue(result.isSuccess());
    }

    @Test
    void shouldSkipIndexingWhenKnowledgeBaseDisabled() {
        VectorStoreAdmin vectorStoreAdmin = mock(VectorStoreAdmin.class);
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);

        RAGDefaultProperties properties = new RAGDefaultProperties();
        properties.setDimension(1024);
        properties.setMetricType("COSINE");

        IndexerNode node = new IndexerNode(
                new ObjectMapper(),
                vectorStoreAdmin,
                milvusClient,
                properties
        );

        IngestionContext context = IngestionContext.builder()
                .taskId("doc-1")
                .pipelineId("pipeline-1")
                .metadata(Map.of("knowledgeBaseEnabled", false))
                .vectorSpaceId(VectorSpaceId.builder().logicalName("kb_collection").build())
                .chunks(List.of(VectorChunk.builder()
                        .chunkId("chunk-1")
                        .index(0)
                        .content("Ragent AI uses multi-channel retrieval.")
                        .embedding(new float[1024])
                        .build()))
                .build();

        NodeResult result = node.execute(context, NodeConfig.builder()
                .nodeId("indexer")
                .nodeType("indexer")
                .build());

        Assertions.assertTrue(result.isSuccess());
        verify(vectorStoreAdmin, never()).ensureVectorSpace(any());
        verify(milvusClient, never()).insert(any());
    }

    @Test
    void shouldPersistKnowledgeScopeMetadataForPipelineVectors() {
        VectorStoreAdmin vectorStoreAdmin = mock(VectorStoreAdmin.class);
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        InsertResp insertResp = mock(InsertResp.class);
        when(vectorStoreAdmin.vectorSpaceExists(any())).thenReturn(true);
        when(milvusClient.insert(any())).thenReturn(insertResp);
        when(insertResp.getInsertCnt()).thenReturn(1L);

        RAGDefaultProperties properties = new RAGDefaultProperties();
        properties.setDimension(1024);
        properties.setMetricType("COSINE");

        IndexerNode node = new IndexerNode(
                new ObjectMapper(),
                vectorStoreAdmin,
                milvusClient,
                properties
        );

        IngestionContext context = IngestionContext.builder()
                .taskId("201")
                .pipelineId("pipeline-1")
                .metadata(Map.of(
                        "kb_id", "101",
                        "doc_id", "201",
                        "embeddingModel", "qwen-emb-8b"
                ))
                .vectorSpaceId(VectorSpaceId.builder().logicalName("kb_collection").build())
                .chunks(List.of(VectorChunk.builder()
                        .chunkId("chunk-1")
                        .index(0)
                        .content("Ragent AI uses multi-channel retrieval.")
                        .embedding(new float[1024])
                        .build()))
                .build();

        NodeResult result = node.execute(context, NodeConfig.builder()
                .nodeId("indexer")
                .nodeType("indexer")
                .build());

        Assertions.assertTrue(result.isSuccess());
        ArgumentCaptor<InsertReq> captor = ArgumentCaptor.forClass(InsertReq.class);
        verify(milvusClient).insert(captor.capture());
        JsonObject row = (JsonObject) captor.getValue().getData().get(0);
        JsonObject metadata = row.getAsJsonObject("metadata");
        Assertions.assertEquals("101", metadata.get("kb_id").getAsString());
        Assertions.assertEquals("201", metadata.get("doc_id").getAsString());
        Assertions.assertEquals("201", metadata.get("task_id").getAsString());
    }
}
