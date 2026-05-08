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

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.IntentDirectedVisualSearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.VisualGlobalSearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.VisualQueryDecider;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.VisualQueryDecider.VisualDecision;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceId;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VisualRetrievalRoutingTests {

    @Test
    void visualGlobalChannelRequiresVisualDecision() {
        SearchChannelProperties properties = new SearchChannelProperties();
        properties.getChannels().getVisualGlobal().setEnabled(true);

        VisualGlobalSearchChannel channel = new VisualGlobalSearchChannel(
                properties,
                new RAGDefaultProperties(),
                mock(KnowledgeBaseMapper.class),
                mock(RetrieverService.class),
                mock(VectorStoreAdmin.class),
                Runnable::run
        );

        SearchContext context = SearchContext.builder()
                .originalQuestion("\u4ecb\u7ecd\u4e00\u4e0b\u6392\u961f\u9650\u6d41\u7684\u8bbe\u8ba1")
                .rewrittenQuestion("\u4ecb\u7ecd\u4e00\u4e0b\u6392\u961f\u9650\u6d41\u7684\u8bbe\u8ba1")
                .intents(List.of())
                .topK(10)
                .build();

        Assertions.assertFalse(channel.isEnabled(context));
    }

    @Test
    void visualGlobalChannelRunsOnlyForVisualQuestionWithoutDirectedTargets() {
        SearchChannelProperties properties = new SearchChannelProperties();
        properties.getChannels().getVisualGlobal().setEnabled(true);

        VisualGlobalSearchChannel channel = new VisualGlobalSearchChannel(
                properties,
                new RAGDefaultProperties(),
                mock(KnowledgeBaseMapper.class),
                mock(RetrieverService.class),
                mock(VectorStoreAdmin.class),
                Runnable::run
        );

        SearchContext context = SearchContext.builder()
                .originalQuestion("\u8fd9\u5f20\u67b6\u6784\u56fe\u91cc\u53f3\u4fa7\u6a21\u5757\u662f\u4ec0\u4e48")
                .rewrittenQuestion("\u89e3\u91ca\u67b6\u6784\u56fe\u53f3\u4fa7\u6a21\u5757")
                .intents(List.of())
                .topK(10)
                .visualRequired(true)
                .targetVisualCollections(List.of())
                .build();

        Assertions.assertTrue(channel.isEnabled(context));
    }

    @Test
    void visualQueryDeciderTargetsIntentImageCollection() {
        SearchChannelProperties properties = new SearchChannelProperties();
        RAGDefaultProperties defaults = new RAGDefaultProperties();
        defaults.setImageCollectionSuffix("_images");

        VectorStoreAdmin vectorStoreAdmin = mock(VectorStoreAdmin.class);
        when(vectorStoreAdmin.vectorSpaceExists(any(VectorSpaceId.class)))
                .thenAnswer(invocation -> {
                    VectorSpaceId spaceId = invocation.getArgument(0);
                    return "kb_policy_images".equals(spaceId.getLogicalName());
                });

        VisualQueryDecider decider = new VisualQueryDecider(properties, defaults, vectorStoreAdmin);
        VisualDecision decision = decider.decide(
                "\u8fd9\u5f20\u622a\u56fe\u91cc\u7684\u5ba1\u6279\u6d41\u7a0b\u600e\u4e48\u8d70",
                List.of(new SubQuestionIntent("\u89e3\u91ca\u5ba1\u6279\u6d41\u7a0b", List.of(NodeScore.builder()
                        .score(0.92D)
                        .node(IntentNode.builder()
                                .id("approval")
                                .kind(IntentKind.KB)
                                .collectionName("kb_policy")
                                .build())
                        .build())))
        );

        Assertions.assertTrue(decision.visualRequired());
        Assertions.assertEquals(List.of("kb_policy_images"), decision.targetVisualCollections());
        Assertions.assertTrue(decision.reason().contains("\u622a\u56fe"));
    }

    @Test
    void visualQueryDeciderRecognizesProductImageWording() {
        VisualQueryDecider decider = new VisualQueryDecider(
                new SearchChannelProperties(),
                new RAGDefaultProperties(),
                mock(VectorStoreAdmin.class)
        );

        VisualDecision overviewDecision = decider.decide("YD-23026\u7684\u6982\u89c8\u56fe\u53d1\u7ed9\u6211", List.of());
        Assertions.assertTrue(overviewDecision.visualRequired());
        Assertions.assertTrue(overviewDecision.reason().contains("\u6982\u89c8\u56fe"));

        VisualDecision imageDecision = decider.decide("YD338CC\u7cfb\u5217\u7684\u76f8\u5173\u8868\u683c\u56fe\u50cf\u7ed9\u6211", List.of());
        Assertions.assertTrue(imageDecision.visualRequired());
        Assertions.assertTrue(imageDecision.reason().contains("\u56fe\u50cf"));
    }

    @Test
    void intentDirectedVisualSearchUsesTargetCollectionsAndVisualEmbedding() {
        SearchChannelProperties properties = new SearchChannelProperties();
        properties.getChannels().getVisualGlobal().setEnabled(false);
        properties.getChannels().getIntentDirectedVisual().setEnabled(true);
        properties.getChannels().getIntentDirectedVisual().setTopKMultiplier(2);
        properties.getChannels().getIntentDirectedVisual().setEmbeddingModel("qwen3-vl-embedding-1024");

        RetrieverService retrieverService = mock(RetrieverService.class);
        when(retrieverService.retrieve(any(RetrieveRequest.class)))
                .thenReturn(List.of(new RetrievedChunk("visual-1", "image evidence", 0.8F)));

        IntentDirectedVisualSearchChannel channel = new IntentDirectedVisualSearchChannel(
                retrieverService,
                properties,
                Runnable::run
        );

        SearchContext context = SearchContext.builder()
                .originalQuestion("\u8fd9\u5f20\u622a\u56fe\u91cc\u7684\u5ba1\u6279\u6309\u94ae\u5728\u54ea\u91cc")
                .rewrittenQuestion("\u89e3\u91ca\u5ba1\u6279\u6309\u94ae\u4f4d\u7f6e")
                .intents(List.of())
                .topK(5)
                .visualRequired(true)
                .targetVisualCollections(List.of("kb_policy_images"))
                .build();

        Assertions.assertTrue(channel.isEnabled(context));

        SearchChannelResult result = channel.search(context);

        Assertions.assertEquals(1, result.getChunks().size());
        ArgumentCaptor<RetrieveRequest> captor = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(retrieverService).retrieve(captor.capture());
        RetrieveRequest request = captor.getValue();
        Assertions.assertEquals("kb_policy_images", request.getCollectionName());
        Assertions.assertEquals("qwen3-vl-embedding-1024", request.getEmbeddingModel());
        Assertions.assertEquals(10, request.getTopK());
    }

    @Test
    void visualGlobalSearchSkipsDisabledKnowledgeBases() {
        SearchChannelProperties properties = new SearchChannelProperties();
        properties.getChannels().getVisualGlobal().setEnabled(true);
        properties.getChannels().getVisualGlobal().setTopKMultiplier(1);
        RAGDefaultProperties defaults = new RAGDefaultProperties();
        defaults.setImageCollectionSuffix("_images");

        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(
                KnowledgeBaseDO.builder().collectionName("enabled_kb").enabled(1).deleted(0).build(),
                KnowledgeBaseDO.builder().collectionName("disabled_kb").enabled(0).deleted(0).build()
        ));
        VectorStoreAdmin vectorStoreAdmin = mock(VectorStoreAdmin.class);
        when(vectorStoreAdmin.vectorSpaceExists(any(VectorSpaceId.class))).thenReturn(true);
        RetrieverService retrieverService = mock(RetrieverService.class);
        when(retrieverService.retrieve(any(RetrieveRequest.class)))
                .thenReturn(List.of(new RetrievedChunk("visual-1", "image evidence", 0.8F)));

        VisualGlobalSearchChannel channel = new VisualGlobalSearchChannel(
                properties,
                defaults,
                knowledgeBaseMapper,
                retrieverService,
                vectorStoreAdmin,
                Runnable::run
        );

        SearchContext context = SearchContext.builder()
                .originalQuestion("给我看产品图片")
                .rewrittenQuestion("给我看产品图片")
                .visualRequired(true)
                .targetVisualCollections(List.of())
                .topK(4)
                .build();

        SearchChannelResult result = channel.search(context);

        Assertions.assertEquals(1, result.getChunks().size());
        ArgumentCaptor<RetrieveRequest> captor = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(retrieverService, times(1)).retrieve(captor.capture());
        Assertions.assertEquals("enabled_kb_images", captor.getValue().getCollectionName());
    }
}
