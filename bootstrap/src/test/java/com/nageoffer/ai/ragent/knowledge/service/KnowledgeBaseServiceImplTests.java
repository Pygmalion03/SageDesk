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

package com.nageoffer.ai.ragent.knowledge.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.infra.model.EmbeddingModelDimensionResolver;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseCreateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBasePageRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeBaseVO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeBaseServiceImpl;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceSpec;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeBaseServiceImplTests {

    @Test
    void shouldCreateVectorSpaceWithEmbeddingDimension() {
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeDocumentMapper knowledgeDocumentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeChunkMapper knowledgeChunkMapper = mock(KnowledgeChunkMapper.class);
        VectorStoreAdmin vectorStoreAdmin = mock(VectorStoreAdmin.class);
        S3Client s3Client = mock(S3Client.class);
        EmbeddingModelDimensionResolver dimensionResolver = mock(EmbeddingModelDimensionResolver.class);
        when(knowledgeBaseMapper.selectCount(any())).thenReturn(0L);
        when(knowledgeBaseMapper.insert(any(KnowledgeBaseDO.class))).thenAnswer(invocation -> {
            KnowledgeBaseDO kb = invocation.getArgument(0);
            kb.setId(1001L);
            return 1;
        });
        when(dimensionResolver.resolveDimension("qwen3-vl-embedding-1024")).thenReturn(1024);
        UserContext.set(LoginUser.builder()
                .userId("1")
                .username("admin")
                .role("admin")
                .build());

        KnowledgeBaseServiceImpl service = new KnowledgeBaseServiceImpl(
                knowledgeBaseMapper,
                knowledgeDocumentMapper,
                knowledgeChunkMapper,
                vectorStoreAdmin,
                s3Client,
                dimensionResolver
        );

        KnowledgeBaseCreateRequest request = new KnowledgeBaseCreateRequest();
        request.setName("YUEDU");
        request.setCollectionName("yuedu1024");
        request.setEmbeddingModel("qwen3-vl-embedding-1024");

        String kbId = service.create(request);

        Assertions.assertEquals("1001", kbId);
        ArgumentCaptor<VectorSpaceSpec> captor = ArgumentCaptor.forClass(VectorSpaceSpec.class);
        verify(vectorStoreAdmin).ensureVectorSpace(captor.capture());
        Assertions.assertEquals(1024, captor.getValue().getDimension());
        Assertions.assertEquals("yuedu1024", captor.getValue().getSpaceId().getLogicalName());
        UserContext.clear();
    }

    @Test
    void shouldRejectInvalidCollectionNameBeforeCreatingExternalResources() {
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeDocumentMapper knowledgeDocumentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeChunkMapper knowledgeChunkMapper = mock(KnowledgeChunkMapper.class);
        VectorStoreAdmin vectorStoreAdmin = mock(VectorStoreAdmin.class);
        S3Client s3Client = mock(S3Client.class);
        EmbeddingModelDimensionResolver dimensionResolver = mock(EmbeddingModelDimensionResolver.class);

        KnowledgeBaseServiceImpl service = new KnowledgeBaseServiceImpl(
                knowledgeBaseMapper,
                knowledgeDocumentMapper,
                knowledgeChunkMapper,
                vectorStoreAdmin,
                s3Client,
                dimensionResolver
        );

        KnowledgeBaseCreateRequest request = new KnowledgeBaseCreateRequest();
        request.setName("bad collection");
        request.setCollectionName("bad_collection");
        request.setEmbeddingModel("qwen3-vl-embedding-1024");

        ClientException exception = Assertions.assertThrows(ClientException.class, () -> service.create(request));

        Assertions.assertTrue(exception.getErrorMessage().contains("Collection 名称只能使用"));
        verify(knowledgeBaseMapper, never()).insert(any(KnowledgeBaseDO.class));
        verifyNoInteractions(s3Client, vectorStoreAdmin, dimensionResolver);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldConvertS3BucketConflictToClientMessage() {
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeDocumentMapper knowledgeDocumentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeChunkMapper knowledgeChunkMapper = mock(KnowledgeChunkMapper.class);
        VectorStoreAdmin vectorStoreAdmin = mock(VectorStoreAdmin.class);
        S3Client s3Client = mock(S3Client.class);
        EmbeddingModelDimensionResolver dimensionResolver = mock(EmbeddingModelDimensionResolver.class);
        when(knowledgeBaseMapper.selectCount(any())).thenReturn(0L);
        when(knowledgeBaseMapper.insert(any(KnowledgeBaseDO.class))).thenAnswer(invocation -> {
            KnowledgeBaseDO kb = invocation.getArgument(0);
            kb.setId(1002L);
            return 1;
        });
        doThrow(S3Exception.builder()
                .message("BucketAlreadyOwnedByYou")
                .statusCode(409)
                .build())
                .when(s3Client).createBucket(any(Consumer.class));
        UserContext.set(LoginUser.builder()
                .userId("1")
                .username("admin")
                .role("admin")
                .build());

        KnowledgeBaseServiceImpl service = new KnowledgeBaseServiceImpl(
                knowledgeBaseMapper,
                knowledgeDocumentMapper,
                knowledgeChunkMapper,
                vectorStoreAdmin,
                s3Client,
                dimensionResolver
        );

        KnowledgeBaseCreateRequest request = new KnowledgeBaseCreateRequest();
        request.setName("YUEDU");
        request.setCollectionName("yuedu1024");
        request.setEmbeddingModel("qwen3-vl-embedding-1024");

        ClientException exception = Assertions.assertThrows(ClientException.class, () -> service.create(request));

        Assertions.assertTrue(exception.getErrorMessage().contains("存储桶已存在"));
        Assertions.assertTrue(exception.getErrorMessage().contains("yuedu1024"));
        verify(vectorStoreAdmin, never()).ensureVectorSpace(any());
        UserContext.clear();
    }

    @Test
    void shouldExposeKnowledgeBaseEnabledStatusFromChunkCounts() {
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeDocumentMapper knowledgeDocumentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeChunkMapper knowledgeChunkMapper = mock(KnowledgeChunkMapper.class);
        VectorStoreAdmin vectorStoreAdmin = mock(VectorStoreAdmin.class);
        S3Client s3Client = mock(S3Client.class);
        EmbeddingModelDimensionResolver dimensionResolver = mock(EmbeddingModelDimensionResolver.class);

        KnowledgeBaseDO kb = KnowledgeBaseDO.builder()
                .id(101L)
                .name("YUEDU")
                .collectionName("yuedu")
                .embeddingModel("qwen-emb")
                .enabled(1)
                .deleted(0)
                .build();
        Page<KnowledgeBaseDO> page = new Page<>(1, 10);
        page.setRecords(List.of(kb));
        page.setTotal(1);
        when(knowledgeBaseMapper.selectPage(any(Page.class), any())).thenReturn(page);
        when(knowledgeDocumentMapper.selectMaps(any())).thenReturn(List.of(
                Map.of("kbId", 101L, "docCount", 2L, "enabledDocCount", 1L)
        ));
        when(knowledgeDocumentMapper.selectList(any())).thenReturn(List.of(
                KnowledgeDocumentDO.builder().id(201L).kbId(101L).enabled(1).deleted(0).build()
        ));
        when(knowledgeChunkMapper.selectMaps(any()))
                .thenReturn(
                        List.of(Map.of("kbId", 101L, "chunkCount", 5L)),
                        List.of(Map.of("kbId", 101L, "enabledChunkCount", 2L))
                );

        KnowledgeBaseServiceImpl service = new KnowledgeBaseServiceImpl(
                knowledgeBaseMapper,
                knowledgeDocumentMapper,
                knowledgeChunkMapper,
                vectorStoreAdmin,
                s3Client,
                dimensionResolver
        );

        IPage<KnowledgeBaseVO> result = service.pageQuery(new KnowledgeBasePageRequest());

        Assertions.assertEquals(1, result.getRecords().size());
        KnowledgeBaseVO record = result.getRecords().get(0);
        Assertions.assertTrue(Boolean.TRUE.equals(record.getEnabled()));
        Assertions.assertTrue(Boolean.TRUE.equals(record.getEffectiveEnabled()));
        Assertions.assertEquals(2L, record.getEnabledChunkCount());
    }

    @Test
    void shouldNotExposeKnowledgeBaseEnabledWhenEnabledChunksBelongToDisabledDocuments() {
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeDocumentMapper knowledgeDocumentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeChunkMapper knowledgeChunkMapper = mock(KnowledgeChunkMapper.class);
        VectorStoreAdmin vectorStoreAdmin = mock(VectorStoreAdmin.class);
        S3Client s3Client = mock(S3Client.class);
        EmbeddingModelDimensionResolver dimensionResolver = mock(EmbeddingModelDimensionResolver.class);

        KnowledgeBaseDO kb = KnowledgeBaseDO.builder()
                .id(101L)
                .name("YUEDU")
                .collectionName("yuedu")
                .embeddingModel("qwen-emb")
                .enabled(1)
                .deleted(0)
                .build();
        Page<KnowledgeBaseDO> page = new Page<>(1, 10);
        page.setRecords(List.of(kb));
        page.setTotal(1);
        when(knowledgeBaseMapper.selectPage(any(Page.class), any())).thenReturn(page);
        when(knowledgeDocumentMapper.selectMaps(any())).thenReturn(List.of(
                Map.of("kbId", 101L, "docCount", 2L, "enabledDocCount", 0L)
        ));
        when(knowledgeDocumentMapper.selectList(any())).thenReturn(List.of());
        when(knowledgeChunkMapper.selectMaps(any())).thenReturn(List.of(
                Map.of("kbId", 101L, "chunkCount", 5L)
        ));

        KnowledgeBaseServiceImpl service = new KnowledgeBaseServiceImpl(
                knowledgeBaseMapper,
                knowledgeDocumentMapper,
                knowledgeChunkMapper,
                vectorStoreAdmin,
                s3Client,
                dimensionResolver
        );

        IPage<KnowledgeBaseVO> result = service.pageQuery(new KnowledgeBasePageRequest());

        KnowledgeBaseVO record = result.getRecords().get(0);
        Assertions.assertTrue(Boolean.TRUE.equals(record.getEnabled()));
        Assertions.assertFalse(Boolean.TRUE.equals(record.getEffectiveEnabled()));
        Assertions.assertEquals(0L, record.getEnabledChunkCount());
    }

    @Test
    void shouldCascadeKnowledgeBaseDisabledStateToDocumentsAndChunks() {
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeDocumentMapper knowledgeDocumentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeChunkMapper knowledgeChunkMapper = mock(KnowledgeChunkMapper.class);
        VectorStoreAdmin vectorStoreAdmin = mock(VectorStoreAdmin.class);
        S3Client s3Client = mock(S3Client.class);
        EmbeddingModelDimensionResolver dimensionResolver = mock(EmbeddingModelDimensionResolver.class);

        KnowledgeBaseDO kb = KnowledgeBaseDO.builder()
                .id(101L)
                .name("YUEDU")
                .collectionName("yuedu")
                .embeddingModel("qwen-emb")
                .enabled(1)
                .deleted(0)
                .build();
        when(knowledgeBaseMapper.selectById("101")).thenReturn(kb);
        KnowledgeBaseServiceImpl service = new KnowledgeBaseServiceImpl(
                knowledgeBaseMapper,
                knowledgeDocumentMapper,
                knowledgeChunkMapper,
                vectorStoreAdmin,
                s3Client,
                dimensionResolver
        );

        service.enable("101", false);

        Assertions.assertEquals(0, kb.getEnabled());
        verify(knowledgeDocumentMapper).update(any(KnowledgeDocumentDO.class), any());
        verify(knowledgeChunkMapper).update(any(), any());
    }

    @Test
    void shouldCascadeKnowledgeBaseEnabledStateToDocumentsAndChunks() {
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeDocumentMapper knowledgeDocumentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeChunkMapper knowledgeChunkMapper = mock(KnowledgeChunkMapper.class);
        VectorStoreAdmin vectorStoreAdmin = mock(VectorStoreAdmin.class);
        S3Client s3Client = mock(S3Client.class);
        EmbeddingModelDimensionResolver dimensionResolver = mock(EmbeddingModelDimensionResolver.class);

        KnowledgeBaseDO kb = KnowledgeBaseDO.builder()
                .id(101L)
                .name("YUEDU")
                .collectionName("yuedu")
                .embeddingModel("qwen-emb")
                .enabled(0)
                .deleted(0)
                .build();
        when(knowledgeBaseMapper.selectById("101")).thenReturn(kb);
        KnowledgeBaseServiceImpl service = new KnowledgeBaseServiceImpl(
                knowledgeBaseMapper,
                knowledgeDocumentMapper,
                knowledgeChunkMapper,
                vectorStoreAdmin,
                s3Client,
                dimensionResolver
        );

        service.enable("101", true);

        Assertions.assertEquals(1, kb.getEnabled());
        verify(knowledgeDocumentMapper).update(any(KnowledgeDocumentDO.class), any());
        verify(knowledgeChunkMapper).update(any(), any());
    }
}
