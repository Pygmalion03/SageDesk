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
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceId;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class VisualQueryDecider {

    private static final List<String> VISUAL_KEYWORDS = List.of(
            "\u56fe\u7247", "\u56fe\u50cf", "\u622a\u56fe", "\u7167\u7247", "\u56fe\u8868",
            "\u6982\u89c8\u56fe", "\u5916\u89c2\u56fe", "\u4ea7\u54c1\u56fe", "\u5b9e\u62cd\u56fe",
            "\u53c2\u6570\u56fe", "\u8be6\u60c5\u56fe", "\u56fe\u4e2d",
            "\u56fe\u91cc", "\u67b6\u6784\u56fe", "\u6d41\u7a0b\u56fe", "\u9875\u9762",
            "\u754c\u9762", "\u5e03\u5c40", "\u770b\u56fe", "\u4e0a\u56fe", "\u4e0b\u56fe",
            "\u8fd9\u5f20", "\u626b\u63cf\u4ef6", "OCR", "ocr", "\u7968\u636e",
            "\u62a5\u8868\u622a\u56fe", "PPT\u622a\u56fe", "PDF\u9875\u9762",
            "\u5de6\u4e0a\u89d2", "\u53f3\u4e0a\u89d2", "\u5de6\u4e0b\u89d2",
            "\u53f3\u4e0b\u89d2", "\u53f3\u4fa7", "\u5de6\u4fa7", "\u7bad\u5934",
            "\u8282\u70b9\u56fe", "\u6a21\u5757\u5173\u7cfb", "\u8868\u683c\u622a\u56fe"
    );

    private final SearchChannelProperties properties;
    private final RAGDefaultProperties ragDefaultProperties;
    private final VectorStoreAdmin vectorStoreAdmin;

    public VisualDecision decide(String originalQuestion, List<SubQuestionIntent> subIntents) {
        String question = StrUtil.blankToDefault(originalQuestion, "");
        String matchedKeyword = VISUAL_KEYWORDS.stream()
                .filter(question::contains)
                .findFirst()
                .orElse(null);

        if (matchedKeyword == null) {
            return new VisualDecision(false, List.of(), "no visual keyword matched");
        }

        List<String> targetCollections = resolveTargetVisualCollections(subIntents);
        String reason = "matched visual keyword: " + matchedKeyword;
        if (CollUtil.isNotEmpty(targetCollections)) {
            reason += ", directed collections: " + targetCollections;
        }
        return new VisualDecision(true, targetCollections, reason);
    }

    private List<String> resolveTargetVisualCollections(List<SubQuestionIntent> subIntents) {
        if (CollUtil.isEmpty(subIntents)) {
            return List.of();
        }

        double minScore = properties.getChannels().getIntentDirected().getMinIntentScore();
        Set<String> collections = new LinkedHashSet<>();
        for (SubQuestionIntent subIntent : subIntents) {
            if (subIntent == null || CollUtil.isEmpty(subIntent.nodeScores())) {
                continue;
            }
            for (NodeScore nodeScore : subIntent.nodeScores()) {
                if (nodeScore == null || nodeScore.getScore() < minScore) {
                    continue;
                }
                IntentNode node = nodeScore.getNode();
                if (node == null || !node.isKB() || StrUtil.isBlank(node.getCollectionName())) {
                    continue;
                }
                String visualCollection = node.getCollectionName() + ragDefaultProperties.getImageCollectionSuffix();
                if (visualCollectionExists(visualCollection)) {
                    collections.add(visualCollection);
                }
            }
        }
        return List.copyOf(collections);
    }

    private boolean visualCollectionExists(String visualCollection) {
        try {
            return vectorStoreAdmin.vectorSpaceExists(VectorSpaceId.builder()
                    .logicalName(visualCollection)
                    .build());
        } catch (Exception ex) {
            log.warn("Failed to check visual collection existence, collection={}", visualCollection, ex);
            return false;
        }
    }

    public record VisualDecision(boolean visualRequired,
                                 List<String> targetVisualCollections,
                                 String reason) {
    }
}
