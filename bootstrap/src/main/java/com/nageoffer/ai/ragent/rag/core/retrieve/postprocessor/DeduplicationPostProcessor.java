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
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class DeduplicationPostProcessor implements SearchResultPostProcessor {

    @Override
    public String getName() {
        return "Deduplication";
    }

    @Override
    public int getOrder() {
        return 1;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return true;
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        Map<String, RetrievedChunk> chunkMap = new LinkedHashMap<>();

        results.stream()
                .sorted((r1, r2) -> Integer.compare(
                        getChannelPriority(r1.getChannelType()),
                        getChannelPriority(r2.getChannelType())
                ))
                .forEach(result -> {
                    for (RetrievedChunk chunk : result.getChunks()) {
                        String key = generateChunkKey(chunk);
                        if (!chunkMap.containsKey(key)) {
                            chunkMap.put(key, chunk);
                            continue;
                        }
                        RetrievedChunk existing = chunkMap.get(key);
                        if (chunk.getScore() > existing.getScore()) {
                            chunkMap.put(key, chunk);
                        }
                    }
                });

        return new ArrayList<>(chunkMap.values());
    }

    private String generateChunkKey(RetrievedChunk chunk) {
        return chunk.getId() != null ? chunk.getId() : String.valueOf(chunk.getText().hashCode());
    }

    private int getChannelPriority(SearchChannelType type) {
        return switch (type) {
            case INTENT_DIRECTED -> 1;
            case INTENT_DIRECTED_VISUAL -> 2;
            case VISUAL_GLOBAL -> 3;
            case KEYWORD_ES -> 4;
            case VECTOR_GLOBAL -> 5;
            default -> 99;
        };
    }
}
