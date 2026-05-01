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

package com.nageoffer.ai.ragent.rag.retrieve;

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.model.ModelHealthStore;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.VectorGlobalSearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.strategy.CollectionParallelRetriever;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VectorGlobalSearchChannelTests {

    @Test
    void shouldSupplementHighConfidenceKbIntentByDefault() {
        VectorGlobalSearchChannel channel = buildChannel(new SearchChannelProperties());

        SearchContext context = SearchContext.builder()
                .intents(List.of(new SubQuestionIntent("Ragent AI advantage", List.of(NodeScore.builder()
                        .score(0.92D)
                        .node(IntentNode.builder()
                                .id("rag-overview")
                                .kind(IntentKind.KB)
                                .collectionName("kb_rag")
                                .build())
                        .build()))))
                .topK(10)
                .build();

        Assertions.assertTrue(channel.isEnabled(context));
    }

    @Test
    void shouldNotSupplementHighConfidenceMcpIntent() {
        VectorGlobalSearchChannel channel = buildChannel(new SearchChannelProperties());

        SearchContext context = SearchContext.builder()
                .intents(List.of(new SubQuestionIntent("query ticket", List.of(NodeScore.builder()
                        .score(0.92D)
                        .node(IntentNode.builder()
                                .id("ticket")
                                .kind(IntentKind.MCP)
                                .mcpToolId("ticket.query")
                                .build())
                        .build()))))
                .topK(10)
                .build();

        Assertions.assertFalse(channel.isEnabled(context));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSkipKnowledgeBasesWithUnavailableEmbeddingModel() {
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        VectorStoreAdmin vectorStoreAdmin = mock(VectorStoreAdmin.class);
        when(vectorStoreAdmin.vectorSpaceExists(any())).thenReturn(false);

        AIModelProperties properties = new AIModelProperties();
        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setId("qwen3-vl-embedding-1024");
        candidate.setProvider("bailian");
        properties.getEmbedding().setDefaultModel("qwen3-vl-embedding-1024");
        properties.getEmbedding().setCandidates(List.of(candidate));
        AIModelProperties.ProviderConfig providerConfig = new AIModelProperties.ProviderConfig();
        providerConfig.setUrl("https://example.com");
        properties.getProviders().put("bailian", providerConfig);
        ModelSelector modelSelector = new ModelSelector(properties, new ModelHealthStore(properties));

        VectorGlobalSearchChannel channel = new VectorGlobalSearchChannel(
                mock(RetrieverService.class),
                new SearchChannelProperties(),
                new RAGDefaultProperties(),
                knowledgeBaseMapper,
                vectorStoreAdmin,
                modelSelector,
                Runnable::run
        );

        List<CollectionParallelRetriever.CollectionTarget> targets =
                (List<CollectionParallelRetriever.CollectionTarget>) ReflectionTestUtils.invokeMethod(
                        channel,
                        "buildCollectionTargets",
                        List.of(
                                KnowledgeBaseDO.builder()
                                        .collectionName("supported-kb")
                                        .embeddingModel("qwen3-vl-embedding-1024")
                                        .build(),
                                KnowledgeBaseDO.builder()
                                        .collectionName("legacy-kb")
                                        .embeddingModel("qwen3-embedding:8b-fp16")
                                        .build()
                        )
                );

        Assertions.assertNotNull(targets);
        Assertions.assertEquals(1, targets.size());
        Assertions.assertEquals("supported-kb", targets.get(0).collectionName());
        Assertions.assertEquals("qwen3-vl-embedding-1024", targets.get(0).embeddingModel());
    }

    private VectorGlobalSearchChannel buildChannel(SearchChannelProperties searchChannelProperties) {
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        VectorStoreAdmin vectorStoreAdmin = mock(VectorStoreAdmin.class);
        when(vectorStoreAdmin.vectorSpaceExists(any())).thenReturn(false);

        AIModelProperties properties = new AIModelProperties();
        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setId("qwen3-vl-embedding-1024");
        candidate.setProvider("bailian");
        properties.getEmbedding().setDefaultModel("qwen3-vl-embedding-1024");
        properties.getEmbedding().setCandidates(List.of(candidate));
        AIModelProperties.ProviderConfig providerConfig = new AIModelProperties.ProviderConfig();
        providerConfig.setUrl("https://example.com");
        properties.getProviders().put("bailian", providerConfig);
        ModelSelector modelSelector = new ModelSelector(properties, new ModelHealthStore(properties));

        return new VectorGlobalSearchChannel(
                mock(RetrieverService.class),
                searchChannelProperties,
                new RAGDefaultProperties(),
                knowledgeBaseMapper,
                vectorStoreAdmin,
                modelSelector,
                Runnable::run
        );
    }
}
