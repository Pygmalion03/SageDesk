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

import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.strategy.IntentParallelRetriever;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntentParallelRetrieverTests {

    @Test
    void shouldPassIntentEmbeddingModelToRetriever() {
        RetrieverService retrieverService = mock(RetrieverService.class);
        ArgumentCaptor<RetrieveRequest> requestCaptor = ArgumentCaptor.forClass(RetrieveRequest.class);
        when(retrieverService.retrieve(requestCaptor.capture())).thenReturn(List.of());

        IntentParallelRetriever retriever = new IntentParallelRetriever(retrieverService, Runnable::run);
        IntentNode node = IntentNode.builder()
                .id("resume-demo")
                .name("Resume Demo")
                .collectionName("resumeragengineeringdemo3")
                .embeddingModel("qwen-emb-local-small")
                .topK(3)
                .build();

        retriever.executeParallelRetrieval(
                "How is the retrieval pipeline designed?",
                List.of(NodeScore.builder().node(node).score(0.9D).build()),
                5,
                2
        );

        RetrieveRequest request = requestCaptor.getValue();
        Assertions.assertEquals("resumeragengineeringdemo3", request.getCollectionName());
        Assertions.assertEquals("qwen-emb-local-small", request.getEmbeddingModel());
        Assertions.assertEquals(6, request.getTopK());
    }
}
