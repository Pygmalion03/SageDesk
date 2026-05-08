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

package com.nageoffer.ai.ragent.rag.core.retrieve.channel;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.strategy.CollectionParallelRetriever;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceId;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Vector global search across all searchable collections.
 */
@Slf4j
@Component
public class VectorGlobalSearchChannel implements SearchChannel {

    private final SearchChannelProperties properties;
    private final RAGDefaultProperties ragDefaultProperties;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final VectorStoreAdmin vectorStoreAdmin;
    private final ModelSelector modelSelector;
    private final CollectionParallelRetriever parallelRetriever;

    public VectorGlobalSearchChannel(RetrieverService retrieverService,
                                     SearchChannelProperties properties,
                                     RAGDefaultProperties ragDefaultProperties,
                                     KnowledgeBaseMapper knowledgeBaseMapper,
                                     VectorStoreAdmin vectorStoreAdmin,
                                     ModelSelector modelSelector,
                                     @Qualifier("ragInnerRetrievalThreadPoolExecutor") Executor innerRetrievalExecutor) {
        this.properties = properties;
        this.ragDefaultProperties = ragDefaultProperties;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.vectorStoreAdmin = vectorStoreAdmin;
        this.modelSelector = modelSelector;
        this.parallelRetriever = new CollectionParallelRetriever(retrieverService, innerRetrievalExecutor);
    }

    @Override
    public String getName() {
        return "VectorGlobalSearch";
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        if (!properties.getChannels().getVectorGlobal().isEnabled()) {
            return false;
        }

        List<NodeScore> allScores = context.getIntents().stream()
                .flatMap(si -> si.nodeScores().stream())
                .toList();
        if (CollUtil.isEmpty(allScores)) {
            log.info("No intent detected, enable vector global search");
            return true;
        }

        if (properties.getChannels().getVectorGlobal().isSupplementIntentDirected()
                && allScores.stream().anyMatch(ns -> ns.getNode() != null && ns.getNode().isKB())) {
            log.info("KB intent detected, enable vector global search as supplementary recall");
            return true;
        }

        double maxScore = allScores.stream()
                .mapToDouble(NodeScore::getScore)
                .max()
                .orElse(0.0);

        double threshold = properties.getChannels().getVectorGlobal().getConfidenceThreshold();
        if (maxScore < threshold) {
            log.info("Intent confidence {} below threshold {}, enable vector global search", maxScore, threshold);
            return true;
        }

        return false;
    }

    @Override
    public boolean isFallbackEnabled(SearchContext context) {
        return properties.getChannels().getVectorGlobal().isEnabled();
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();

        try {
            log.info("Run vector global search, question={}", context.getMainQuestion());

            List<CollectionParallelRetriever.CollectionTarget> collections = getAllKBCollections();
            if (collections.isEmpty()) {
                log.warn("No searchable text collection found for vector global search");
                return SearchChannelResult.builder()
                        .channelType(SearchChannelType.VECTOR_GLOBAL)
                        .channelName(getName())
                        .chunks(List.of())
                        .confidence(0.0)
                        .latencyMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            int topKMultiplier = properties.getChannels().getVectorGlobal().getTopKMultiplier();
            List<RetrievedChunk> allChunks = retrieveFromAllCollections(
                    context.getMainQuestion(),
                    collections,
                    context.getTopK() * topKMultiplier
            );

            long latency = System.currentTimeMillis() - startTime;
            log.info("Vector global search completed, chunkCount={}, latencyMs={}", allChunks.size(), latency);

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.VECTOR_GLOBAL)
                    .channelName(getName())
                    .chunks(allChunks)
                    .confidence(allChunks.isEmpty() ? 0.0 : 0.7)
                    .latencyMs(latency)
                    .metadata(Map.of("collectionCount", collections.size()))
                    .build();

        } catch (Exception e) {
            log.error("Vector global search failed", e);
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.VECTOR_GLOBAL)
                    .channelName(getName())
                    .chunks(List.of())
                    .confidence(0.0)
                    .latencyMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    private List<CollectionParallelRetriever.CollectionTarget> getAllKBCollections() {
        List<KnowledgeBaseDO> kbList = knowledgeBaseMapper.selectList(
                Wrappers.query(KnowledgeBaseDO.class)
                        .select("collection_name", "embedding_model", "enabled")
                        .eq("deleted", 0)
                        .eq("enabled", 1)
        );
        return buildCollectionTargets(kbList);
    }

    private List<CollectionParallelRetriever.CollectionTarget> buildCollectionTargets(List<KnowledgeBaseDO> kbList) {
        Map<String, CollectionParallelRetriever.CollectionTarget> targets = new LinkedHashMap<>();
        for (KnowledgeBaseDO kb : kbList) {
            if (!isEnabled(kb)) {
                continue;
            }
            String collectionName = kb.getCollectionName();
            if (collectionName == null || collectionName.isBlank()) {
                continue;
            }
            if (!modelSelector.supportsEmbeddingModel(kb.getEmbeddingModel())) {
                log.debug("Skip vector global search collection due to unavailable embedding model, collection={}, embeddingModel={}",
                        collectionName, kb.getEmbeddingModel());
                continue;
            }
            targets.putIfAbsent(
                    collectionName,
                    new CollectionParallelRetriever.CollectionTarget(collectionName, kb.getEmbeddingModel())
            );
        }

        String defaultCollectionName = ragDefaultProperties.getCollectionName();
        if (defaultCollectionName != null
                && !defaultCollectionName.isBlank()
                && vectorStoreAdmin.vectorSpaceExists(VectorSpaceId.builder().logicalName(defaultCollectionName).build())) {
            targets.putIfAbsent(
                    defaultCollectionName,
                    new CollectionParallelRetriever.CollectionTarget(defaultCollectionName, null)
            );
        }

        return new ArrayList<>(targets.values());
    }

    private boolean isEnabled(KnowledgeBaseDO kb) {
        return kb != null && (kb.getEnabled() == null || Integer.valueOf(1).equals(kb.getEnabled()));
    }

    private List<RetrievedChunk> retrieveFromAllCollections(String question,
                                                            List<CollectionParallelRetriever.CollectionTarget> collections,
                                                            int topK) {
        return parallelRetriever.executeParallelRetrieval(question, collections, topK);
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.VECTOR_GLOBAL;
    }
}
