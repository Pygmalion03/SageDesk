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
import com.nageoffer.ai.ragent.rag.core.mcp.MCPParameterExtractor;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPToolRegistry;
import com.nageoffer.ai.ragent.rag.core.prompt.ContextFormatter;
import com.nageoffer.ai.ragent.rag.core.retrieve.MultiChannelRetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalEngineFallbackTests {

    @Test
    void shouldFallbackToGlobalRetrievalWhenIntentListIsEmptyButQuestionExists() {
        String question = "Ragent AI \u76f8\u5bf9\u4e8e\u666e\u901a RAG \u6709\u4ec0\u4e48\u4f18\u52bf\uff1f";
        RetrievedChunk chunk = new RetrievedChunk("chunk-1", "advantage", 0.9F);

        ContextFormatter contextFormatter = mock(ContextFormatter.class);
        when(contextFormatter.formatKbContext(anyList(), anyMap(), anyInt()))
                .thenReturn("kb context");

        MultiChannelRetrievalEngine multiChannelRetrievalEngine = mock(MultiChannelRetrievalEngine.class);
        when(multiChannelRetrievalEngine.retrieveKnowledgeChannels(eq(question), anyList(), eq(10)))
                .thenReturn(List.of(chunk));

        RetrievalEngine retrievalEngine = new RetrievalEngine(
                contextFormatter,
                mock(MCPParameterExtractor.class),
                mock(MCPToolRegistry.class),
                multiChannelRetrievalEngine,
                Runnable::run,
                Runnable::run
        );

        RetrievalContext result = retrievalEngine.retrieve(question, List.of(), 10);

        Assertions.assertTrue(result.hasKb());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubQuestionIntent>> captor = ArgumentCaptor.forClass(List.class);
        verify(multiChannelRetrievalEngine).retrieveKnowledgeChannels(eq(question), captor.capture(), eq(10));

        List<SubQuestionIntent> fallbackIntents = captor.getValue();
        Assertions.assertEquals(1, fallbackIntents.size());
        Assertions.assertEquals(question, fallbackIntents.get(0).subQuestion());
        Assertions.assertTrue(fallbackIntents.get(0).nodeScores().isEmpty());

        verify(contextFormatter).formatKbContext(eq(List.of()), eq(Map.of("multi_channel", List.of(chunk))), eq(10));
    }
}
