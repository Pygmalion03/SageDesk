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

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceId;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Slf4j
@Component
public class VisualGlobalSearchChannel implements SearchChannel {

    private final SearchChannelProperties properties;
    private final RAGDefaultProperties ragDefaultProperties;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final RetrieverService retrieverService;
    private final VectorStoreAdmin vectorStoreAdmin;
    private final Executor innerRetrievalExecutor;

    public VisualGlobalSearchChannel(SearchChannelProperties properties,
                                     RAGDefaultProperties ragDefaultProperties,
                                     KnowledgeBaseMapper knowledgeBaseMapper,
                                     RetrieverService retrieverService,
                                     VectorStoreAdmin vectorStoreAdmin,
                                     @Qualifier("ragInnerRetrievalThreadPoolExecutor") Executor innerRetrievalExecutor) {
        this.properties = properties;
        this.ragDefaultProperties = ragDefaultProperties;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.retrieverService = retrieverService;
        this.vectorStoreAdmin = vectorStoreAdmin;
        this.innerRetrievalExecutor = innerRetrievalExecutor;
    }

    @Override
    public String getName() {
        return "VisualGlobalSearch";
    }

    @Override
    public int getPriority() {
        return 5;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return properties.getChannels().getVisualGlobal().isEnabled()
                && context != null
                && context.isVisualRequired()
                && (context.getTargetVisualCollections() == null || context.getTargetVisualCollections().isEmpty());
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();
        try {
            List<String> collections = getAllVisualCollections();
            if (collections.isEmpty()) {
                return SearchChannelResult.builder()
                        .channelType(SearchChannelType.VISUAL_GLOBAL)
                        .channelName(getName())
                        .chunks(List.of())
                        .confidence(0.0)
                        .latencyMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            int topK = context.getTopK() * properties.getChannels().getVisualGlobal().getTopKMultiplier();
            List<CompletableFuture<List<RetrievedChunk>>> futures = collections.stream()
                    .map(collectionName -> CompletableFuture.supplyAsync(
                            () -> retrieveFromCollection(context.getMainQuestion(), collectionName, topK),
                            innerRetrievalExecutor
                    ))
                    .toList();

            List<RetrievedChunk> chunks = futures.stream()
                    .map(CompletableFuture::join)
                    .flatMap(List::stream)
                    .toList();

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.VISUAL_GLOBAL)
                    .channelName(getName())
                    .chunks(chunks)
                    .confidence(chunks.isEmpty() ? 0.0 : 0.65)
                    .latencyMs(System.currentTimeMillis() - startTime)
                    .metadata(java.util.Map.of("collectionCount", collections.size()))
                    .build();
        } catch (Exception e) {
            log.error("Visual global search failed", e);
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.VISUAL_GLOBAL)
                    .channelName(getName())
                    .chunks(List.of())
                    .confidence(0.0)
                    .latencyMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.VISUAL_GLOBAL;
    }

    private List<RetrievedChunk> retrieveFromCollection(String question, String collectionName, int topK) {
        try {
            return retrieverService.retrieve(RetrieveRequest.builder()
                    .query(question)
                    .collectionName(collectionName)
                    .embeddingModel(properties.getChannels().getVisualGlobal().getEmbeddingModel())
                    .topK(topK)
                    .build());
        } catch (Exception e) {
            log.warn("Visual retrieval failed on collection {}", collectionName, e);
            return List.of();
        }
    }

    private List<String> getAllVisualCollections() {
        Set<String> collections = new LinkedHashSet<>();
        List<KnowledgeBaseDO> kbList = knowledgeBaseMapper.selectList(
                Wrappers.query(KnowledgeBaseDO.class)
                        .select("collection_name", "enabled")
                        .eq("deleted", 0)
                        .eq("enabled", 1)
        );
        for (KnowledgeBaseDO kb : kbList) {
            if (!isEnabled(kb)) {
                continue;
            }
            String collectionName = kb.getCollectionName();
            if (collectionName == null || collectionName.isBlank()) {
                continue;
            }
            String visualCollectionName = collectionName + ragDefaultProperties.getImageCollectionSuffix();
            if (vectorStoreAdmin.vectorSpaceExists(VectorSpaceId.builder().logicalName(visualCollectionName).build())) {
                collections.add(visualCollectionName);
            }
        }
        String defaultCollectionName = ragDefaultProperties.getCollectionName();
        if (defaultCollectionName != null && !defaultCollectionName.isBlank()) {
            String defaultVisualCollection = defaultCollectionName + ragDefaultProperties.getImageCollectionSuffix();
            if (vectorStoreAdmin.vectorSpaceExists(VectorSpaceId.builder().logicalName(defaultVisualCollection).build())) {
                collections.add(defaultVisualCollection);
            }
        }
        return new ArrayList<>(collections);
    }

    private boolean isEnabled(KnowledgeBaseDO kb) {
        return kb != null && (kb.getEnabled() == null || Integer.valueOf(1).equals(kb.getEnabled()));
    }
}
