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

package com.nageoffer.ai.ragent.rag.vector;

import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.infra.model.EmbeddingModelDimensionResolver;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.core.vector.MilvusVectorStoreService;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.InsertResp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MilvusVectorStoreServiceTests {

    @Test
    void shouldUseEmbeddingModelDimensionWhenIndexingChunks() {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        KnowledgeBaseMapper kbMapper = mock(KnowledgeBaseMapper.class);
        RAGDefaultProperties defaultProperties = new RAGDefaultProperties();
        defaultProperties.setDimension(4096);
        EmbeddingModelDimensionResolver dimensionResolver = mock(EmbeddingModelDimensionResolver.class);
        MilvusVectorStoreService service = new MilvusVectorStoreService(
                milvusClient,
                kbMapper,
                defaultProperties,
                dimensionResolver
        );

        KnowledgeBaseDO kb = KnowledgeBaseDO.builder()
                .id(1L)
                .collectionName("yuedu1024")
                .embeddingModel("qwen3-vl-embedding-1024")
                .build();
        when(kbMapper.selectById("1")).thenReturn(kb);
        when(dimensionResolver.resolveDimension("qwen3-vl-embedding-1024")).thenReturn(1024);
        InsertResp insertResp = mock(InsertResp.class);
        when(insertResp.getInsertCnt()).thenReturn(1L);
        when(milvusClient.insert(any(InsertReq.class))).thenReturn(insertResp);

        VectorChunk chunk = VectorChunk.builder()
                .chunkId("chunk-1")
                .index(0)
                .content("catalog")
                .embedding(new float[1024])
                .build();

        service.indexDocumentChunks("1", "doc-1", List.of(chunk));

        ArgumentCaptor<InsertReq> captor = ArgumentCaptor.forClass(InsertReq.class);
        verify(milvusClient).insert(captor.capture());
        JsonObject row = (JsonObject) captor.getValue().getData().get(0);
        Assertions.assertEquals(1024, row.getAsJsonArray("embedding").size());
    }

    @Test
    void shouldRejectChunkWhenVectorDimensionStillMismatchesResolvedModel() {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        KnowledgeBaseMapper kbMapper = mock(KnowledgeBaseMapper.class);
        RAGDefaultProperties defaultProperties = new RAGDefaultProperties();
        defaultProperties.setDimension(4096);
        EmbeddingModelDimensionResolver dimensionResolver = mock(EmbeddingModelDimensionResolver.class);
        MilvusVectorStoreService service = new MilvusVectorStoreService(
                milvusClient,
                kbMapper,
                defaultProperties,
                dimensionResolver
        );

        KnowledgeBaseDO kb = KnowledgeBaseDO.builder()
                .id(1L)
                .collectionName("yuedu1024")
                .embeddingModel("qwen3-vl-embedding-1024")
                .build();
        when(kbMapper.selectById("1")).thenReturn(kb);
        when(dimensionResolver.resolveDimension("qwen3-vl-embedding-1024")).thenReturn(1024);

        VectorChunk chunk = VectorChunk.builder()
                .chunkId("chunk-1")
                .index(0)
                .content("catalog")
                .embedding(new float[4096])
                .build();

        ClientException ex = Assertions.assertThrows(ClientException.class,
                () -> service.indexDocumentChunks("1", "doc-1", List.of(chunk)));
        Assertions.assertTrue(ex.getMessage().contains("1024"));
    }

    @Test
    void shouldDeleteDocumentVectorsFromTextAndVisualCollections() {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        KnowledgeBaseMapper kbMapper = mock(KnowledgeBaseMapper.class);
        RAGDefaultProperties defaultProperties = new RAGDefaultProperties();
        defaultProperties.setImageCollectionSuffix("_images");
        EmbeddingModelDimensionResolver dimensionResolver = mock(EmbeddingModelDimensionResolver.class);
        MilvusVectorStoreService service = new MilvusVectorStoreService(
                milvusClient,
                kbMapper,
                defaultProperties,
                dimensionResolver
        );

        KnowledgeBaseDO kb = KnowledgeBaseDO.builder()
                .id(101L)
                .collectionName("yuedu")
                .embeddingModel("qwen3-vl-embedding-1024")
                .build();
        when(kbMapper.selectById("101")).thenReturn(kb);
        DeleteResp deleteResp = mock(DeleteResp.class);
        when(deleteResp.getDeleteCnt()).thenReturn(1L);
        when(milvusClient.delete(any(DeleteReq.class))).thenReturn(deleteResp);

        service.deleteDocumentVectors("101", "201");

        ArgumentCaptor<DeleteReq> captor = ArgumentCaptor.forClass(DeleteReq.class);
        verify(milvusClient, times(2)).delete(captor.capture());
        Assertions.assertEquals("yuedu", captor.getAllValues().get(0).getCollectionName());
        Assertions.assertEquals("yuedu_images", captor.getAllValues().get(1).getCollectionName());
        Assertions.assertTrue(captor.getAllValues().get(0).getFilter().contains("metadata[\"task_id\"] == \"201\""));
        Assertions.assertTrue(captor.getAllValues().get(1).getFilter().contains("metadata[\"doc_id\"] == \"201\""));
    }
}
