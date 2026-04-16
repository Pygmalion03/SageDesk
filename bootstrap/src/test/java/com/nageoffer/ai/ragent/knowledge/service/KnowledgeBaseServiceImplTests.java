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

import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.infra.model.EmbeddingModelDimensionResolver;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseCreateRequest;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeBaseServiceImpl;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceSpec;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseServiceImplTests {

    @Test
    void shouldCreateVectorSpaceWithEmbeddingDimension() {
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeDocumentMapper knowledgeDocumentMapper = mock(KnowledgeDocumentMapper.class);
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
}
