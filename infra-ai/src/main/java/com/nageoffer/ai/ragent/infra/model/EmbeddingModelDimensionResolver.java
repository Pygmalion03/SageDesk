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

package com.nageoffer.ai.ragent.infra.model;

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class EmbeddingModelDimensionResolver {

    private final AIModelProperties aiModelProperties;

    public Integer resolveDimension(String modelId) {
        if (!StringUtils.hasText(modelId)
                || aiModelProperties.getEmbedding() == null
                || aiModelProperties.getEmbedding().getCandidates() == null) {
            return null;
        }
        return aiModelProperties.getEmbedding().getCandidates().stream()
                .filter(candidate -> candidate != null
                        && (modelId.equals(candidate.getId()) || modelId.equals(candidate.getModel())))
                .map(AIModelProperties.ModelCandidate::getDimension)
                .filter(dimension -> dimension != null && dimension > 0)
                .findFirst()
                .orElse(null);
    }
}
