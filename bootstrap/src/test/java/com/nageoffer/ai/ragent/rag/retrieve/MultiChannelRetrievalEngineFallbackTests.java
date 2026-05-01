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
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieve.MultiChannelRetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.VisualQueryDecider;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MultiChannelRetrievalEngineFallbackTests {

    @Test
    void shouldRunVectorFallbackWhenHighConfidenceDirectedSearchReturnsNoChunks() {
        SearchChannel directed = new StubChannel(
                "IntentDirectedSearch",
                SearchChannelType.INTENT_DIRECTED,
                1,
                true,
                false,
                List.of()
        );
        SearchChannel vectorFallback = new StubChannel(
                "VectorGlobalSearch",
                SearchChannelType.VECTOR_GLOBAL,
                10,
                false,
                true,
                List.of(new RetrievedChunk("global-1", "Ragent AI advantage", 0.8F))
        );

        VisualQueryDecider visualQueryDecider = mock(VisualQueryDecider.class);
        when(visualQueryDecider.decide(any(), anyList()))
                .thenReturn(new VisualQueryDecider.VisualDecision(false, List.of(), "no visual keyword matched"));

        MultiChannelRetrievalEngine engine = new MultiChannelRetrievalEngine(
                List.of(directed, vectorFallback),
                List.of(),
                visualQueryDecider,
                Runnable::run
        );

        List<RetrievedChunk> chunks = engine.retrieveKnowledgeChannels(
                "Ragent AI \u76f8\u5bf9\u4e8e\u666e\u901a RAG \u6709\u4ec0\u4e48\u4f18\u52bf\uff1f",
                List.of(new SubQuestionIntent("Ragent AI \u4f18\u52bf", List.of(NodeScore.builder()
                        .score(0.92D)
                        .node(IntentNode.builder()
                                .id("rag-overview")
                                .collectionName("empty-directed-kb")
                                .build())
                        .build()))),
                10
        );

        Assertions.assertEquals(1, chunks.size());
        Assertions.assertEquals("global-1", chunks.get(0).getId());
    }

    private record StubChannel(String name,
                               SearchChannelType type,
                               int priority,
                               boolean enabled,
                               boolean fallbackEnabled,
                               List<RetrievedChunk> chunks) implements SearchChannel {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public int getPriority() {
            return priority;
        }

        @Override
        public boolean isEnabled(SearchContext context) {
            return enabled;
        }

        @Override
        public boolean isFallbackEnabled(SearchContext context) {
            return fallbackEnabled;
        }

        @Override
        public SearchChannelResult search(SearchContext context) {
            return SearchChannelResult.builder()
                    .channelType(type)
                    .channelName(name)
                    .chunks(chunks)
                    .confidence(chunks.isEmpty() ? 0.0D : 0.7D)
                    .latencyMs(1L)
                    .build();
        }

        @Override
        public SearchChannelType getType() {
            return type;
        }
    }
}
