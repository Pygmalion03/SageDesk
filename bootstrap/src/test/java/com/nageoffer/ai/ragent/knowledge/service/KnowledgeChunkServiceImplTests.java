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

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.infra.token.TokenCounterService;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkBatchRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkPageRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeChunkVO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeChunkServiceImpl;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeChunkServiceImplTests {

    @Test
    void shouldPromoteDocumentAndKnowledgeBaseWhenEnablingChunkWithoutTouchingVectors() {
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        TokenCounterService tokenCounterService = mock(TokenCounterService.class);
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);

        KnowledgeDocumentDO document = KnowledgeDocumentDO.builder()
                .id(201L)
                .kbId(101L)
                .enabled(0)
                .build();
        KnowledgeChunkDO chunk = KnowledgeChunkDO.builder()
                .id(301L)
                .kbId(101L)
                .docId(201L)
                .content("chunk")
                .chunkIndex(0)
                .enabled(0)
                .build();
        when(documentMapper.selectById("201")).thenReturn(document);
        when(chunkMapper.selectById("301")).thenReturn(chunk);

        KnowledgeChunkServiceImpl service = new KnowledgeChunkServiceImpl(
                chunkMapper,
                documentMapper,
                knowledgeBaseMapper,
                embeddingService,
                tokenCounterService,
                vectorStoreService
        );

        service.enableChunk("201", "301", true);

        Assertions.assertEquals(1, chunk.getEnabled());
        verify(chunkMapper).updateById(chunk);
        verify(documentMapper).update(any(KnowledgeDocumentDO.class), any());
        verify(knowledgeBaseMapper).update(any(KnowledgeBaseDO.class), any());
        verifyNoInteractions(vectorStoreService, embeddingService);
    }

    @Test
    void shouldDisableChunkWithoutDeletingVectorAndDisableParentsWhenNoEnabledChunksRemain() {
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        TokenCounterService tokenCounterService = mock(TokenCounterService.class);
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);

        KnowledgeDocumentDO document = KnowledgeDocumentDO.builder()
                .id(201L)
                .kbId(101L)
                .enabled(1)
                .build();
        KnowledgeChunkDO chunk = KnowledgeChunkDO.builder()
                .id(301L)
                .kbId(101L)
                .docId(201L)
                .content("chunk")
                .chunkIndex(0)
                .enabled(1)
                .build();
        when(documentMapper.selectById("201")).thenReturn(document);
        when(chunkMapper.selectById("301")).thenReturn(chunk);
        when(chunkMapper.selectCount(any())).thenReturn(0L);

        KnowledgeChunkServiceImpl service = new KnowledgeChunkServiceImpl(
                chunkMapper,
                documentMapper,
                knowledgeBaseMapper,
                embeddingService,
                tokenCounterService,
                vectorStoreService
        );

        service.enableChunk("201", "301", false);

        Assertions.assertEquals(0, chunk.getEnabled());
        verify(chunkMapper).updateById(chunk);
        verify(documentMapper).update(any(KnowledgeDocumentDO.class), any());
        verify(knowledgeBaseMapper).update(any(KnowledgeBaseDO.class), any());
        verify(vectorStoreService, never()).deleteChunkById("101", "301");
        verify(vectorStoreService, never()).indexDocumentChunks(any(), any(), any());
        verifyNoInteractions(embeddingService);
    }

    @Test
    void shouldDisableKnowledgeBaseWhenNoEnabledChunksRemainEvenIfDocumentsAreStaleEnabled() {
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        TokenCounterService tokenCounterService = mock(TokenCounterService.class);
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);

        KnowledgeDocumentDO document = KnowledgeDocumentDO.builder()
                .id(201L)
                .kbId(101L)
                .enabled(1)
                .build();
        KnowledgeChunkDO chunk = KnowledgeChunkDO.builder()
                .id(301L)
                .kbId(101L)
                .docId(201L)
                .content("chunk")
                .chunkIndex(0)
                .enabled(1)
                .build();
        when(documentMapper.selectById("201")).thenReturn(document);
        when(chunkMapper.selectById("301")).thenReturn(chunk);
        when(chunkMapper.selectCount(any())).thenReturn(0L);
        when(documentMapper.selectCount(any())).thenReturn(1L);

        KnowledgeChunkServiceImpl service = new KnowledgeChunkServiceImpl(
                chunkMapper,
                documentMapper,
                knowledgeBaseMapper,
                embeddingService,
                tokenCounterService,
                vectorStoreService
        );

        service.enableChunk("201", "301", false);

        ArgumentCaptor<KnowledgeBaseDO> captor = ArgumentCaptor.forClass(KnowledgeBaseDO.class);
        verify(knowledgeBaseMapper).update(captor.capture(), any());
        Assertions.assertEquals(0, captor.getValue().getEnabled());
        verifyNoInteractions(vectorStoreService, embeddingService);
    }

    @Test
    void shouldBatchToggleChunksWithoutVectorRewrite() {
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        TokenCounterService tokenCounterService = mock(TokenCounterService.class);
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);

        KnowledgeDocumentDO document = KnowledgeDocumentDO.builder()
                .id(201L)
                .kbId(101L)
                .enabled(0)
                .build();
        KnowledgeChunkDO chunk = KnowledgeChunkDO.builder()
                .id(301L)
                .kbId(101L)
                .docId(201L)
                .content("chunk")
                .chunkIndex(0)
                .enabled(0)
                .build();
        when(documentMapper.selectById("201")).thenReturn(document);
        when(chunkMapper.selectByIds(List.of(301L))).thenReturn(List.of(chunk));
        KnowledgeChunkBatchRequest request = new KnowledgeChunkBatchRequest();
        request.setChunkIds(List.of(301L));

        KnowledgeChunkServiceImpl service = new KnowledgeChunkServiceImpl(
                chunkMapper,
                documentMapper,
                knowledgeBaseMapper,
                embeddingService,
                tokenCounterService,
                vectorStoreService
        );

        service.batchEnable("201", request);

        Assertions.assertEquals(1, chunk.getEnabled());
        verify(documentMapper).update(any(KnowledgeDocumentDO.class), any());
        verify(knowledgeBaseMapper).update(any(KnowledgeBaseDO.class), any());
        verifyNoInteractions(vectorStoreService, embeddingService);
    }

    @Test
    void shouldShowChunksAsEffectivelyDisabledWhenKnowledgeBaseDisabled() {
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        TokenCounterService tokenCounterService = mock(TokenCounterService.class);
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);

        when(documentMapper.selectById("201")).thenReturn(KnowledgeDocumentDO.builder()
                .id(201L)
                .kbId(101L)
                .enabled(1)
                .build());
        when(knowledgeBaseMapper.selectById(101L)).thenReturn(KnowledgeBaseDO.builder()
                .id(101L)
                .enabled(0)
                .build());

        Page<KnowledgeChunkDO> page = new Page<>(1, 10);
        page.setRecords(List.of(KnowledgeChunkDO.builder()
                .id(301L)
                .kbId(101L)
                .docId(201L)
                .chunkIndex(0)
                .content("chunk")
                .tokenCount(12)
                .enabled(1)
                .build()));
        page.setTotal(1);
        when(chunkMapper.selectPage(any(Page.class), any())).thenReturn(page);

        KnowledgeChunkServiceImpl service = new KnowledgeChunkServiceImpl(
                chunkMapper,
                documentMapper,
                knowledgeBaseMapper,
                embeddingService,
                tokenCounterService,
                vectorStoreService
        );

        KnowledgeChunkPageRequest request = new KnowledgeChunkPageRequest();
        request.setCurrent(1);
        request.setSize(10);
        KnowledgeChunkVO record = service.pageQuery("201", request).getRecords().get(0);

        Assertions.assertEquals(1, record.getEnabled());
        Assertions.assertFalse(Boolean.TRUE.equals(record.getEffectiveEnabled()));
    }

    @Test
    void shouldReturnNoEnabledChunksWhenKnowledgeBaseDisabledAndFilteringEnabled() {
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        TokenCounterService tokenCounterService = mock(TokenCounterService.class);
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);

        when(documentMapper.selectById("201")).thenReturn(KnowledgeDocumentDO.builder()
                .id(201L)
                .kbId(101L)
                .enabled(1)
                .build());
        when(knowledgeBaseMapper.selectById(101L)).thenReturn(KnowledgeBaseDO.builder()
                .id(101L)
                .enabled(0)
                .build());

        KnowledgeChunkServiceImpl service = new KnowledgeChunkServiceImpl(
                chunkMapper,
                documentMapper,
                knowledgeBaseMapper,
                embeddingService,
                tokenCounterService,
                vectorStoreService
        );

        KnowledgeChunkPageRequest request = new KnowledgeChunkPageRequest();
        request.setCurrent(1);
        request.setSize(10);
        request.setEnabled(1);

        Page<KnowledgeChunkVO> result = (Page<KnowledgeChunkVO>) service.pageQuery("201", request);

        Assertions.assertEquals(0, result.getTotal());
        Assertions.assertTrue(result.getRecords().isEmpty());
        verify(chunkMapper, never()).selectPage(any(Page.class), any());
    }

    @Test
    void shouldSkipVectorRebuildWhenKnowledgeBaseDisabled() {
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        TokenCounterService tokenCounterService = mock(TokenCounterService.class);
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);

        when(documentMapper.selectById("201")).thenReturn(KnowledgeDocumentDO.builder()
                .id(201L)
                .kbId(101L)
                .enabled(1)
                .build());
        when(knowledgeBaseMapper.selectById(101L)).thenReturn(KnowledgeBaseDO.builder()
                .id(101L)
                .enabled(0)
                .build());

        KnowledgeChunkServiceImpl service = new KnowledgeChunkServiceImpl(
                chunkMapper,
                documentMapper,
                knowledgeBaseMapper,
                embeddingService,
                tokenCounterService,
                vectorStoreService
        );

        service.rebuildByDocId("201");

        verify(vectorStoreService).deleteDocumentVectors("101", "201");
        verify(vectorStoreService, never()).indexDocumentChunks(any(), any(), any());
    }
}
