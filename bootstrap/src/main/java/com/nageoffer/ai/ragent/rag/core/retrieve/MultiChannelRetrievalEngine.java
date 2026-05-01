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

package com.nageoffer.ai.ragent.rag.core.retrieve;

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.VisualQueryDecider;
import com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor.SearchResultPostProcessor;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 多通道检索引擎
 * <p>
 * 负责协调多个检索通道和后置处理器：
 * 1. 并行执行所有启用的检索通道
 * 2. 依次执行后置处理器链
 * 3. 返回最终的检索结果
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiChannelRetrievalEngine {

    private final List<SearchChannel> searchChannels;
    private final List<SearchResultPostProcessor> postProcessors;
    private final VisualQueryDecider visualQueryDecider;
    @Qualifier("ragRetrievalThreadPoolExecutor")
    private final Executor ragRetrievalExecutor;

    /**
     * 执行多通道检索（仅 KB 场景）
     *
     * @param subIntents 子问题意图列表
     * @param topK       期望返回的结果数量
     * @return 检索到的 Chunk 列表
     */
    @RagTraceNode(name = "multi-channel-retrieval", type = "RETRIEVE_CHANNEL")
    public List<RetrievedChunk> retrieveKnowledgeChannels(List<SubQuestionIntent> subIntents, int topK) {
        return retrieveKnowledgeChannels(null, subIntents, topK);
    }

    @RagTraceNode(name = "multi-channel-retrieval", type = "RETRIEVE_CHANNEL")
    public List<RetrievedChunk> retrieveKnowledgeChannels(String originalQuestion,
                                                          List<SubQuestionIntent> subIntents,
                                                          int topK) {
        // 构建检索上下文
        SearchContext context = buildSearchContext(originalQuestion, subIntents, topK);
        recordRetrievalInputs(context);

        // 【阶段1：多通道并行检索】
        List<SearchChannelResult> channelResults = executeSearchChannels(context);
        if (CollUtil.isEmpty(channelResults)) {
            RagTraceContext.putNodeExtra("resultCount", 0);
            RagTraceContext.putNodeExtra("resultChunkIds", List.of());
            RagTraceContext.putNodeExtra("resultChunks", List.of());
            return List.of();
        }

        // 【阶段2：后置处理器链】
        return executePostProcessors(channelResults, context);
    }

    private List<SearchChannel> selectFallbackChannels(SearchContext context,
                                                       List<SearchChannel> enabledChannels,
                                                       List<SearchChannelResult> results) {
        boolean hasAnyChunk = results.stream()
                .anyMatch(result -> CollUtil.isNotEmpty(result.getChunks()));
        if (hasAnyChunk) {
            return List.of();
        }

        return searchChannels.stream()
                .filter(channel -> channel.isFallbackEnabled(context))
                .filter(channel -> enabledChannels.stream()
                        .noneMatch(enabled -> Objects.equals(enabled.getName(), channel.getName())))
                .sorted(Comparator.comparingInt(SearchChannel::getPriority))
                .toList();
    }

    /**
     * 执行所有启用的检索通道
     */
    private List<SearchChannelResult> executeSearchChannels(SearchContext context) {
        // 过滤启用的通道
        List<SearchChannel> enabledChannels = searchChannels.stream()
                .filter(channel -> channel.isEnabled(context))
                .sorted(Comparator.comparingInt(SearchChannel::getPriority))
                .toList();

        if (enabledChannels.isEmpty()) {
            RagTraceContext.putNodeExtra("enabledChannels", List.of());
        }

        RagTraceContext.putNodeExtra(
                "enabledChannels",
                enabledChannels.stream().map(SearchChannel::getName).toList()
        );

        log.info("启用的检索通道：{}",
                enabledChannels.stream().map(SearchChannel::getName).toList());

        // 并行执行所有通道
        List<CompletableFuture<SearchChannelResult>> futures = enabledChannels.stream()
                .map(channel -> CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                log.info("执行检索通道：{}", channel.getName());
                                return channel.search(context);
                            } catch (Exception e) {
                                log.error("检索通道 {} 执行失败", channel.getName(), e);
                                return SearchChannelResult.builder()
                                        .channelType(channel.getType())
                                        .channelName(channel.getName())
                                        .chunks(List.of())
                                        .confidence(0.0)
                                        .build();
                            }
                        },
                        ragRetrievalExecutor
                ))
                .toList();

        // 等待所有通道完成并统计
        int successCount = 0;
        int failureCount = 0;
        int totalChunks = 0;

        List<SearchChannelResult> results = futures.stream()
                .map(future -> {
                    try {
                        return future.join();
                    } catch (Exception e) {
                        log.error("获取通道检索结果失败", e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();

        // 打印详细统计信息
        List<SearchChannel> fallbackChannels = selectFallbackChannels(context, enabledChannels, results);
        RagTraceContext.putNodeExtra(
                "fallbackChannels",
                fallbackChannels.stream().map(SearchChannel::getName).toList()
        );
        if (CollUtil.isNotEmpty(fallbackChannels)) {
            log.info("Initial retrieval returned no chunks, run fallback channels: {}",
                    fallbackChannels.stream().map(SearchChannel::getName).toList());
            List<CompletableFuture<SearchChannelResult>> fallbackFutures = fallbackChannels.stream()
                    .map(channel -> CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    log.info("Run fallback search channel: {}", channel.getName());
                                    return channel.search(context);
                                } catch (Exception e) {
                                    log.error("Fallback search channel {} failed", channel.getName(), e);
                                    return SearchChannelResult.builder()
                                            .channelType(channel.getType())
                                            .channelName(channel.getName())
                                            .chunks(List.of())
                                            .confidence(0.0)
                                            .build();
                                }
                            },
                            ragRetrievalExecutor
                    ))
                    .toList();
            List<SearchChannelResult> fallbackResults = fallbackFutures.stream()
                    .map(future -> {
                        try {
                            return future.join();
                        } catch (Exception e) {
                            log.error("Failed to get fallback search channel result", e);
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .toList();
            results = java.util.stream.Stream.concat(results.stream(), fallbackResults.stream()).toList();
        }

        for (SearchChannelResult result : results) {
            int chunkCount = result.getChunks().size();
            totalChunks += chunkCount;

            if (chunkCount > 0) {
                successCount++;
                log.info("通道 {} 完成 ✓ - 检索到 {} 个 Chunk，置信度：{}，耗时：{}ms",
                        result.getChannelName(),
                        chunkCount,
                        result.getConfidence(),
                        result.getLatencyMs()
                );
            } else {
                failureCount++;
                log.warn("通道 {} 完成但无结果 - 置信度：{}，耗时：{}ms",
                        result.getChannelName(),
                        result.getConfidence(),
                        result.getLatencyMs()
                );
            }
        }

        log.info("多通道检索统计 - 总通道数: {}, 有结果: {}, 无结果: {}, Chunk 总数: {}",
                enabledChannels.size() + fallbackChannels.size(), successCount, failureCount, totalChunks);

        RagTraceContext.putNodeExtra("channelCount", enabledChannels.size() + fallbackChannels.size());
        RagTraceContext.putNodeExtra("channelSuccessCount", successCount);
        RagTraceContext.putNodeExtra("channelEmptyCount", failureCount);
        RagTraceContext.putNodeExtra("initialChunkCount", totalChunks);
        RagTraceContext.putNodeExtra("channelResults", summarizeChannelResults(results));
        return results;
    }

    /**
     * 执行后置处理器链
     */
    private List<RetrievedChunk> executePostProcessors(List<SearchChannelResult> results,
                                                       SearchContext context) {
        // 过滤启用的处理器并排序
        List<SearchResultPostProcessor> enabledProcessors = postProcessors.stream()
                .filter(processor -> processor.isEnabled(context))
                .sorted(Comparator.comparingInt(SearchResultPostProcessor::getOrder))
                .toList();

        if (enabledProcessors.isEmpty()) {
            log.warn("没有启用的后置处理器，直接返回原始结果");
            List<RetrievedChunk> rawChunks = results.stream()
                    .flatMap(r -> r.getChunks().stream())
                    .collect(Collectors.toList());
            RagTraceContext.putNodeExtra("postProcessors", List.of());
            RagTraceContext.putNodeExtra("resultCount", rawChunks.size());
            RagTraceContext.putNodeExtra("resultChunkIds", summarizeChunkIds(rawChunks));
            RagTraceContext.putNodeExtra("resultChunks", summarizeChunks(rawChunks));
            return rawChunks;
        }

        // 初始 Chunk 列表（所有通道的结果合并）
        List<RetrievedChunk> chunks = results.stream()
                .flatMap(r -> r.getChunks().stream())
                .collect(Collectors.toList());

        int initialSize = chunks.size();
        RagTraceContext.putNodeExtra(
                "postProcessors",
                enabledProcessors.stream().map(SearchResultPostProcessor::getName).toList()
        );

        // 依次执行处理器
        for (SearchResultPostProcessor processor : enabledProcessors) {
            try {
                int beforeSize = chunks.size();
                chunks = processor.process(chunks, results, context);
                int afterSize = chunks.size();

                log.info("后置处理器 {} 完成 - 输入: {} 个 Chunk, 输出: {} 个 Chunk, 变化: {}",
                        processor.getName(),
                        beforeSize,
                        afterSize,
                        (afterSize - beforeSize > 0 ? "+" : "") + (afterSize - beforeSize)
                );
            } catch (Exception e) {
                log.error("后置处理器 {} 执行失败，跳过该处理器", processor.getName(), e);
                // 继续执行下一个处理器，不中断整个链
            }
        }

        log.info("后置处理器链执行完成 - 初始: {} 个 Chunk, 最终: {} 个 Chunk",
                initialSize, chunks.size());

        RagTraceContext.putNodeExtra("resultCount", chunks.size());
        RagTraceContext.putNodeExtra("resultChunkIds", summarizeChunkIds(chunks));
        RagTraceContext.putNodeExtra("resultChunks", summarizeChunks(chunks));
        return chunks;
    }

    /**
     * 构建检索上下文
     */
    private SearchContext buildSearchContext(String originalQuestion, List<SubQuestionIntent> subIntents, int topK) {
        String rewrittenQuestion = CollUtil.isEmpty(subIntents) ? "" : subIntents.get(0).subQuestion();
        String sourceQuestion = originalQuestion != null && !originalQuestion.isBlank()
                ? originalQuestion
                : rewrittenQuestion;
        VisualQueryDecider.VisualDecision visualDecision = visualQueryDecider.decide(sourceQuestion, subIntents);

        return SearchContext.builder()
                .originalQuestion(sourceQuestion)
                .rewrittenQuestion(rewrittenQuestion)
                .subQuestions(
                        CollUtil.isEmpty(subIntents)
                                ? List.of()
                                : subIntents.stream().map(SubQuestionIntent::subQuestion).toList()
                )
                .intents(CollUtil.isEmpty(subIntents) ? List.of() : subIntents)
                .topK(topK)
                .visualRequired(visualDecision.visualRequired())
                .targetVisualCollections(visualDecision.targetVisualCollections())
                .visualDecisionReason(visualDecision.reason())
                .build();
    }

    private void recordRetrievalInputs(SearchContext context) {
        RagTraceContext.putNodeExtra("topK", context.getTopK());
        RagTraceContext.putNodeExtra("mainQuestion", context.getMainQuestion());
        RagTraceContext.putNodeExtra("subQuestions", context.getSubQuestions());
        RagTraceContext.putNodeExtra("intentScores", summarizeIntentScores(context.getIntents()));
        RagTraceContext.putNodeExtra("visualRequired", context.isVisualRequired());
        RagTraceContext.putNodeExtra("targetVisualCollections", context.getTargetVisualCollections());
        RagTraceContext.putNodeExtra("visualDecisionReason", context.getVisualDecisionReason());
    }

    private List<Map<String, Object>> summarizeIntentScores(List<SubQuestionIntent> subIntents) {
        if (CollUtil.isEmpty(subIntents)) {
            return List.of();
        }
        return subIntents.stream()
                .filter(Objects::nonNull)
                .flatMap(si -> {
                    if (CollUtil.isEmpty(si.nodeScores())) {
                        return java.util.stream.Stream.of(Map.<String, Object>of(
                                "subQuestion", si.subQuestion(),
                                "nodeCount", 0
                        ));
                    }
                    return si.nodeScores().stream()
                            .filter(Objects::nonNull)
                            .map(ns -> {
                                Map<String, Object> summary = new LinkedHashMap<>();
                                summary.put("subQuestion", si.subQuestion());
                                summary.put("score", ns.getScore());
                                if (ns.getNode() != null) {
                                    summary.put("nodeId", ns.getNode().getId());
                                    summary.put("nodeName", ns.getNode().getName());
                                    summary.put("kind", ns.getNode().getKind() == null ? null : ns.getNode().getKind().name());
                                    summary.put("collectionName", ns.getNode().getCollectionName());
                                }
                                return summary;
                            });
                })
                .toList();
    }

    private List<Map<String, Object>> summarizeChannelResults(List<SearchChannelResult> results) {
        return results.stream().map(result -> {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("channelName", result.getChannelName());
            summary.put("channelType", result.getChannelType() == null ? null : result.getChannelType().name());
            summary.put("confidence", result.getConfidence());
            summary.put("latencyMs", result.getLatencyMs());
            summary.put("chunkCount", result.getChunks() == null ? 0 : result.getChunks().size());
            summary.put("chunkIds", summarizeChunkIds(result.getChunks()));
            if (result.getMetadata() != null && !result.getMetadata().isEmpty()) {
                summary.put("metadata", result.getMetadata());
            }
            return summary;
        }).toList();
    }

    private List<String> summarizeChunkIds(List<RetrievedChunk> chunks) {
        if (CollUtil.isEmpty(chunks)) {
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (RetrievedChunk chunk : chunks) {
            if (chunk == null || chunk.getId() == null || chunk.getId().isBlank()) {
                continue;
            }
            ids.add(chunk.getId());
        }
        return ids.stream().limit(10).toList();
    }

    private List<Map<String, Object>> summarizeChunks(List<RetrievedChunk> chunks) {
        if (CollUtil.isEmpty(chunks)) {
            return List.of();
        }
        return chunks.stream()
                .filter(Objects::nonNull)
                .limit(10)
                .map(chunk -> {
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("id", chunk.getId());
                    summary.put("score", chunk.getScore());
                    return summary;
                })
                .toList();
    }
}
