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

package com.nageoffer.ai.ragent.infra.rerank;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.model.ModelHealthStore;
import com.nageoffer.ai.ragent.infra.model.ModelRoutingExecutor;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoutingRerankServiceTests {

    @Test
    void explicitRerankModelFallsBackToNextCandidate() {
        AIModelProperties properties = properties();
        ModelHealthStore healthStore = new ModelHealthStore(properties);
        RoutingRerankService service = new RoutingRerankService(
                new ModelSelector(properties, healthStore),
                new ModelRoutingExecutor(healthStore),
                List.of(new FailingRerankClient(), new NoopRerankClient())
        );

        List<RetrievedChunk> reranked = service.rerank(
                "query",
                List.of(
                        new RetrievedChunk("a", "first", 0.1f),
                        new RetrievedChunk("b", "second", 0.2f)
                ),
                1,
                "qwen3-vl-rerank-local"
        );

        assertEquals(1, reranked.size());
        assertEquals("a", reranked.get(0).getId());
    }

    private AIModelProperties properties() {
        AIModelProperties properties = new AIModelProperties();
        AIModelProperties.ProviderConfig localHf = new AIModelProperties.ProviderConfig();
        localHf.setUrl("http://127.0.0.1:8126");
        localHf.setEndpoints(java.util.Map.of("rerank", "/v1/rerank"));

        properties.setProviders(new HashMap<>());
        properties.getProviders().put("local-hf", localHf);

        AIModelProperties.ModelCandidate local = new AIModelProperties.ModelCandidate();
        local.setId("qwen3-vl-rerank-local");
        local.setProvider("local-hf");
        local.setModel("Qwen/Qwen3-VL-Reranker-2B");
        local.setPriority(0);

        AIModelProperties.ModelCandidate noop = new AIModelProperties.ModelCandidate();
        noop.setId("rerank-noop");
        noop.setProvider("noop");
        noop.setModel("noop");
        noop.setPriority(100);

        AIModelProperties.ModelGroup rerank = new AIModelProperties.ModelGroup();
        rerank.setDefaultModel("qwen3-vl-rerank-local");
        rerank.setCandidates(List.of(local, noop));
        properties.setRerank(rerank);
        return properties;
    }

    private static class FailingRerankClient implements RerankClient {

        @Override
        public String provider() {
            return "local-hf";
        }

        @Override
        public List<RetrievedChunk> rerank(String query,
                                           List<RetrievedChunk> candidates,
                                           int topN,
                                           ModelTarget target) {
            throw new IllegalStateException("local rerank unavailable");
        }
    }
}
