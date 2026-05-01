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
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.strategy.CollectionParallelRetriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@Slf4j
@Component
public class IntentDirectedVisualSearchChannel implements SearchChannel {

    private final SearchChannelProperties properties;
    private final CollectionParallelRetriever parallelRetriever;

    public IntentDirectedVisualSearchChannel(RetrieverService retrieverService,
                                             SearchChannelProperties properties,
                                             @Qualifier("ragInnerRetrievalThreadPoolExecutor") Executor innerRetrievalExecutor) {
        this.properties = properties;
        this.parallelRetriever = new CollectionParallelRetriever(retrieverService, innerRetrievalExecutor);
    }

    @Override
    public String getName() {
        return "IntentDirectedVisualSearch";
    }

    @Override
    public int getPriority() {
        return 2;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return properties.getChannels().getIntentDirectedVisual().isEnabled()
                && context != null
                && context.isVisualRequired()
                && CollUtil.isNotEmpty(context.getTargetVisualCollections());
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();
        try {
            List<String> collectionNames = new LinkedHashSet<>(context.getTargetVisualCollections())
                    .stream()
                    .toList();
            if (collectionNames.isEmpty()) {
                return emptyResult(startTime);
            }

            String embeddingModel = properties.getChannels().getIntentDirectedVisual().getEmbeddingModel();
            List<CollectionParallelRetriever.CollectionTarget> targets = collectionNames.stream()
                    .map(collectionName -> new CollectionParallelRetriever.CollectionTarget(collectionName, embeddingModel))
                    .toList();

            int topK = context.getTopK() * properties.getChannels().getIntentDirectedVisual().getTopKMultiplier();
            List<RetrievedChunk> chunks = parallelRetriever.executeParallelRetrieval(
                    context.getMainQuestion(),
                    targets,
                    topK
            );

            long latency = System.currentTimeMillis() - startTime;
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.INTENT_DIRECTED_VISUAL)
                    .channelName(getName())
                    .chunks(chunks)
                    .confidence(chunks.isEmpty() ? 0.0 : 0.75)
                    .latencyMs(latency)
                    .metadata(Map.of(
                            "collectionCount", collectionNames.size(),
                            "collections", collectionNames
                    ))
                    .build();
        } catch (Exception e) {
            log.error("Intent directed visual search failed", e);
            return emptyResult(startTime);
        }
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.INTENT_DIRECTED_VISUAL;
    }

    private SearchChannelResult emptyResult(long startTime) {
        return SearchChannelResult.builder()
                .channelType(SearchChannelType.INTENT_DIRECTED_VISUAL)
                .channelName(getName())
                .chunks(List.of())
                .confidence(0.0)
                .latencyMs(System.currentTimeMillis() - startTime)
                .build();
    }
}
