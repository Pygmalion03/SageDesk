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

package com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.infra.rerank.RerankService;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class RerankPostProcessor implements SearchResultPostProcessor {

    private static final int DEFAULT_TOP_K = 10;
    private static final Pattern DOTTED_LEADER_PAGE_NUMBER = Pattern.compile(".*\\.{4,}\\s*\\d+\\s*$");
    private static final Pattern MODEL_CODE_PATTERN = Pattern.compile("[A-Za-z]{1,8}[-_\\s]*\\d[A-Za-z0-9_-]*");

    private final RerankService rerankService;
    private final SearchChannelProperties searchChannelProperties;

    @Override
    public String getName() {
        return "Rerank";
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return searchChannelProperties.getRerank().isEnabled();
    }

    @Override
    @RagTraceNode(name = "rerank", type = "POST_PROCESS")
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        RagTraceContext.putNodeExtra("inputCount", chunks.size());
        RagTraceContext.putNodeExtra("requestedTopK", context.getTopK());
        if (chunks.isEmpty()) {
            log.info("Skip rerank because candidate chunks are empty");
            RagTraceContext.putNodeExtra("candidateCount", 0);
            RagTraceContext.putNodeExtra("outputCount", 0);
            return chunks;
        }

        CandidateSelection candidateSelection = prepareCandidates(chunks, context);
        List<RetrievedChunk> candidates = candidateSelection.candidates();
        RagTraceContext.putNodeExtra("candidateLimit", candidateSelection.limit());
        RagTraceContext.putNodeExtra("candidateCount", candidates.size());
        RagTraceContext.putNodeExtra("filteredVisualNavigationCount", candidateSelection.filteredNavigationCount());
        RagTraceContext.putNodeExtra("candidateLimitTrimmedCount", candidateSelection.limitTrimmedCount());
        RagTraceContext.putNodeExtra("candidateExactMatchRescuedCount", candidateSelection.exactMatchRescuedCount());
        RagTraceContext.putNodeExtra("candidateDroppedCount", chunks.size() - candidates.size());

        boolean hasVisualChunk = candidates.stream().anyMatch(RetrievedChunk::isVisual);
        List<RetrievedChunk> rerankedChunks;
        if (hasVisualChunk) {
            String rerankModel = searchChannelProperties.getChannels().getVisualGlobal().getRerankModel();
            RagTraceContext.putNodeExtra("rerankModel", rerankModel);
            rerankedChunks = rerankService.rerank(context.getMainQuestion(), candidates, context.getTopK(), rerankModel);
        } else {
            rerankedChunks = rerankService.rerank(context.getMainQuestion(), candidates, context.getTopK());
        }
        RagTraceContext.putNodeExtra("outputCount", rerankedChunks.size());
        return rerankedChunks;
    }

    private CandidateSelection prepareCandidates(List<RetrievedChunk> chunks, SearchContext context) {
        List<RetrievedChunk> filteredChunks = filterVisualNavigationNoise(chunks, context);
        int filteredNavigationCount = chunks.size() - filteredChunks.size();
        int candidateLimit = resolveCandidateLimit(context);
        if (filteredChunks.size() <= candidateLimit) {
            return new CandidateSelection(filteredChunks, candidateLimit, filteredNavigationCount, 0, 0);
        }
        CandidateWindow candidateWindow = selectCandidateWindow(filteredChunks, candidateLimit, context);
        return new CandidateSelection(
                candidateWindow.candidates(),
                candidateLimit,
                filteredNavigationCount,
                filteredChunks.size() - candidateLimit,
                candidateWindow.exactMatchRescuedCount()
        );
    }

    private CandidateWindow selectCandidateWindow(List<RetrievedChunk> chunks, int candidateLimit, SearchContext context) {
        List<RetrievedChunk> selected = new ArrayList<>(chunks.subList(0, candidateLimit));
        Set<String> selectedKeys = new LinkedHashSet<>();
        selected.forEach(chunk -> selectedKeys.add(candidateKey(chunk)));

        Set<String> rescuedKeys = new LinkedHashSet<>();
        String question = context == null ? null : context.getMainQuestion();
        for (int i = candidateLimit; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            if (!isExactModelMatch(question, chunk)) {
                continue;
            }
            String key = candidateKey(chunk);
            if (selectedKeys.add(key)) {
                selected.add(chunk);
                rescuedKeys.add(key);
            }
        }

        while (selected.size() > candidateLimit) {
            int removeIndex = findLastNonExactMatchIndex(selected, question);
            if (removeIndex < 0) {
                removeIndex = selected.size() - 1;
            }
            String removedKey = candidateKey(selected.remove(removeIndex));
            rescuedKeys.remove(removedKey);
            selectedKeys.remove(removedKey);
        }
        return new CandidateWindow(selected, rescuedKeys.size());
    }

    private int findLastNonExactMatchIndex(List<RetrievedChunk> chunks, String question) {
        for (int i = chunks.size() - 1; i >= 0; i--) {
            if (!isExactModelMatch(question, chunks.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private boolean isExactModelMatch(String question, RetrievedChunk chunk) {
        Set<String> modelTokens = extractModelTokens(question);
        if (modelTokens.isEmpty() || chunk == null) {
            return false;
        }
        String normalizedText = normalizeModelText(chunk.getText());
        if (chunk.getMetadata() != null) {
            Object summary = chunk.getMetadata().get("summary");
            if (summary != null) {
                normalizedText += normalizeModelText(String.valueOf(summary));
            }
        }
        if (normalizedText.isBlank()) {
            return false;
        }
        return modelTokens.stream().anyMatch(normalizedText::contains);
    }

    private Set<String> extractModelTokens(String question) {
        Set<String> tokens = new LinkedHashSet<>();
        if (question == null || question.isBlank()) {
            return tokens;
        }
        java.util.regex.Matcher matcher = MODEL_CODE_PATTERN.matcher(question);
        while (matcher.find()) {
            String normalized = normalizeModelText(matcher.group());
            if (normalized.length() >= 4) {
                tokens.add(normalized);
            }
        }
        return tokens;
    }

    private String normalizeModelText(String value) {
        if (value == null) {
            return "";
        }
        return value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private String candidateKey(RetrievedChunk chunk) {
        if (chunk == null) {
            return "";
        }
        return chunk.getId() == null ? String.valueOf(System.identityHashCode(chunk)) : chunk.getId();
    }

    private List<RetrievedChunk> filterVisualNavigationNoise(List<RetrievedChunk> chunks, SearchContext context) {
        if (!searchChannelProperties.getRerank().isFilterVisualNavigationNoise()
                || context == null
                || !context.isVisualRequired()
                || isNavigationQuestion(context.getMainQuestion())) {
            return chunks;
        }

        boolean hasUsefulVisualChunk = chunks.stream()
                .anyMatch(chunk -> chunk.isVisual() && !looksLikeVisualNavigationNoise(chunk));
        if (!hasUsefulVisualChunk) {
            return chunks;
        }
        return chunks.stream()
                .filter(chunk -> !looksLikeVisualNavigationNoise(chunk))
                .toList();
    }

    private boolean isNavigationQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = question.toLowerCase(Locale.ROOT);
        return normalized.contains("目录")
                || normalized.contains("table of contents")
                || normalized.contains("catalog")
                || normalized.contains("toc");
    }

    private boolean looksLikeVisualNavigationNoise(RetrievedChunk chunk) {
        if (chunk == null || !chunk.isVisual() || chunk.getText() == null || chunk.getText().isBlank()) {
            return false;
        }
        String text = chunk.getText();
        String normalized = text.toLowerCase(Locale.ROOT);
        if (normalized.contains("table of contents")) {
            return true;
        }

        int dottedLeaderLines = 0;
        int productPathLines = 0;
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (DOTTED_LEADER_PAGE_NUMBER.matcher(trimmed).matches()) {
                dottedLeaderLines++;
            }
            if (trimmed.contains("/products/")) {
                productPathLines++;
            }
        }

        return (normalized.startsWith("content") && dottedLeaderLines >= 1)
                || dottedLeaderLines >= 3
                || (productPathLines >= 2 && dottedLeaderLines >= 1);
    }

    private int resolveCandidateLimit(SearchContext context) {
        int topK = context != null && context.getTopK() > 0 ? context.getTopK() : DEFAULT_TOP_K;
        SearchChannelProperties.Rerank properties = searchChannelProperties.getRerank();
        int multiplier = Math.max(1, properties.getCandidateMultiplier());
        int multipliedLimit = Math.max(topK, topK * multiplier);
        int maxCandidates = properties.getMaxCandidates();
        if (maxCandidates <= 0) {
            return multipliedLimit;
        }
        return Math.max(topK, Math.min(multipliedLimit, maxCandidates));
    }

    private record CandidateSelection(
            List<RetrievedChunk> candidates,
            int limit,
            int filteredNavigationCount,
            int limitTrimmedCount,
            int exactMatchRescuedCount) {
    }

    private record CandidateWindow(
            List<RetrievedChunk> candidates,
            int exactMatchRescuedCount) {
    }
}
