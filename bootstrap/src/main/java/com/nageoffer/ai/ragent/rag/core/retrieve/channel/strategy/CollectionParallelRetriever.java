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

package com.nageoffer.ai.ragent.rag.core.retrieve.channel.strategy;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.AbstractParallelRetriever;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Collection parallel retriever.
 */
@Slf4j
public class CollectionParallelRetriever
        extends AbstractParallelRetriever<CollectionParallelRetriever.CollectionTarget> {

    public record CollectionTarget(String collectionName, String embeddingModel) {
    }

    private final RetrieverService retrieverService;

    public CollectionParallelRetriever(RetrieverService retrieverService, Executor executor) {
        super(executor);
        this.retrieverService = retrieverService;
    }

    @Override
    protected List<RetrievedChunk> createRetrievalTask(String question, CollectionTarget target, int topK) {
        try {
            return retrieverService.retrieve(
                    RetrieveRequest.builder()
                            .collectionName(target.collectionName())
                            .embeddingModel(target.embeddingModel())
                            .query(question)
                            .topK(topK)
                            .build()
            );
        } catch (Exception e) {
            log.error("Collection retrieval failed, collection={}, embeddingModel={}, error={}",
                    target.collectionName(), target.embeddingModel(), e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    protected String getTargetIdentifier(CollectionTarget target) {
        return "Collection: " + target.collectionName();
    }

    @Override
    protected String getStatisticsName() {
        return "global retrieval";
    }
}
