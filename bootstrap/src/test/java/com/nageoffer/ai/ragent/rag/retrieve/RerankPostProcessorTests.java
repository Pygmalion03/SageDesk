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
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.infra.rerank.RerankService;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor.DeduplicationPostProcessor;
import com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor.RerankPostProcessor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

class RerankPostProcessorTests {

    @Test
    void shouldLimitCandidatesBeforeCallingRerank() {
        CapturingRerankService rerankService = new CapturingRerankService();
        RerankPostProcessor processor = new RerankPostProcessor(rerankService, new SearchChannelProperties());
        List<RetrievedChunk> chunks = IntStream.range(0, 30)
                .mapToObj(i -> new RetrievedChunk("chunk-" + i, "candidate " + i, 1.0F - i * 0.01F))
                .toList();

        List<RetrievedChunk> processed = processor.process(chunks, List.of(), SearchContext.builder()
                .originalQuestion("Ragent AI advantage")
                .topK(4)
                .build());

        Assertions.assertEquals(12, rerankService.lastCandidates.size());
        Assertions.assertEquals(4, processed.size());
    }

    @Test
    void shouldFilterVisualNavigationNoiseBeforeRerank() {
        CapturingRerankService rerankService = new CapturingRerankService();
        RerankPostProcessor processor = new RerankPostProcessor(rerankService, new SearchChannelProperties());
        RetrievedChunk tocPage = visualChunk(
                "toc-page",
                "CONTENT\nYD-SSJ Series ........................................ 70\n"
                        + "Hard Drive Shredders /products/data-destruction/hard-drive ........ 71\n"
                        + "Storage Chip Shredders /products/data-destruction/chip ............ 74",
                0.98F
        );
        RetrievedChunk productPage = visualChunk(
                "product-page",
                "YD-338CC Series Product Overview Technical Specifications Paper Capacity Price USD",
                0.79F
        );

        processor.process(List.of(tocPage, productPage), List.of(channelResult(tocPage, productPage)),
                SearchContext.builder()
                        .originalQuestion("YD338CC series related tables and images")
                        .topK(2)
                        .visualRequired(true)
                        .build());

        Assertions.assertEquals("product-page", rerankService.lastCandidates.get(0).getId());
        Assertions.assertFalse(rerankService.lastCandidates.stream().anyMatch(chunk -> "toc-page".equals(chunk.getId())));
    }

    @Test
    void shouldKeepExactModelMatchesWhenCandidateLimitWouldTrimThem() {
        CapturingRerankService rerankService = new CapturingRerankService();
        RerankPostProcessor processor = new RerankPostProcessor(rerankService, new SearchChannelProperties());
        List<RetrievedChunk> chunks = IntStream.range(0, 30)
                .mapToObj(i -> new RetrievedChunk("chunk-" + i, "generic product candidate " + i, 1.0F - i * 0.01F))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        chunks.set(25, new RetrievedChunk(
                "exact-yd338cc-table",
                "DModel YD-338CC40-HD YD-338CC-HD YD-338CCM Technical Specifications Price USD",
                0.25F
        ));

        processor.process(chunks, List.of(), SearchContext.builder()
                .originalQuestion("YD338CC系列的相关表格和图像")
                .topK(4)
                .visualRequired(true)
                .build());

        Assertions.assertEquals(12, rerankService.lastCandidates.size());
        Assertions.assertTrue(rerankService.lastCandidates.stream()
                .anyMatch(chunk -> "exact-yd338cc-table".equals(chunk.getId())));
    }

    @Test
    void shouldExposePostProcessorsAsTraceNodes() throws NoSuchMethodException {
        Method rerankProcess = RerankPostProcessor.class.getMethod(
                "process",
                List.class,
                List.class,
                SearchContext.class
        );
        Method dedupProcess = DeduplicationPostProcessor.class.getMethod(
                "process",
                List.class,
                List.class,
                SearchContext.class
        );

        RagTraceNode rerankTrace = rerankProcess.getAnnotation(RagTraceNode.class);
        RagTraceNode dedupTrace = dedupProcess.getAnnotation(RagTraceNode.class);

        Assertions.assertNotNull(rerankTrace);
        Assertions.assertEquals("rerank", rerankTrace.name());
        Assertions.assertEquals("POST_PROCESS", rerankTrace.type());
        Assertions.assertNotNull(dedupTrace);
        Assertions.assertEquals("deduplication", dedupTrace.name());
        Assertions.assertEquals("POST_PROCESS", dedupTrace.type());
    }

    private SearchChannelResult channelResult(RetrievedChunk... chunks) {
        return SearchChannelResult.builder()
                .channelName("VisualGlobalSearch")
                .channelType(SearchChannelType.VISUAL_GLOBAL)
                .chunks(List.of(chunks))
                .confidence(1.0D)
                .build();
    }

    private RetrievedChunk visualChunk(String id, String text, Float score) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("content_type", "visual");
        metadata.put("image_uri", "doc/page-" + id + ".png");
        return new RetrievedChunk(id, text, score, metadata);
    }

    private static final class CapturingRerankService implements RerankService {

        private List<RetrievedChunk> lastCandidates = List.of();

        @Override
        public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN) {
            lastCandidates = candidates;
            return candidates.stream().limit(topN).toList();
        }

        @Override
        public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, String modelId) {
            return rerank(query, candidates, topN);
        }
    }
}
