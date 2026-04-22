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

package com.nageoffer.ai.ragent.rag.service.support;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class VisualAnswerAppendixService {

    private final MediaPreviewService mediaPreviewService;
    private final RAGDefaultProperties ragDefaultProperties;

    public String buildMarkdown(Map<String, List<RetrievedChunk>> intentChunks) {
        if (intentChunks == null || intentChunks.isEmpty()) {
            return "";
        }

        int imageLimit = ragDefaultProperties.getVisualAnswerImageLimit() == null
                ? 4
                : Math.max(ragDefaultProperties.getVisualAnswerImageLimit(), 0);
        if (imageLimit == 0) {
            return "";
        }

        Map<String, RetrievedChunk> uniqueChunks = new LinkedHashMap<>();
        intentChunks.values().stream()
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .filter(RetrievedChunk::isVisual)
                .forEach(chunk -> {
                    String imageUri = extractImageUri(chunk);
                    if (StrUtil.isBlank(imageUri) || uniqueChunks.size() >= imageLimit) {
                        return;
                    }
                    uniqueChunks.putIfAbsent(imageUri, chunk);
                });

        if (uniqueChunks.isEmpty()) {
            return "";
        }

        StringBuilder appendix = new StringBuilder("\n\n---\n\n## 相关图片\n");
        int index = 1;
        for (Map.Entry<String, RetrievedChunk> entry : uniqueChunks.entrySet()) {
            RetrievedChunk chunk = entry.getValue();
            String previewUrl = mediaPreviewService.buildPreviewUrl(entry.getKey());
            if (StrUtil.isBlank(previewUrl)) {
                continue;
            }
            appendix.append("\n### 图片 ").append(index).append("\n\n");
            appendix.append("![")
                    .append(buildAltText(chunk, index).replace("[", " ").replace("]", " "))
                    .append("](<")
                    .append(previewUrl)
                    .append(">)\n\n");

            String summary = extractSummary(chunk);
            if (StrUtil.isNotBlank(summary)) {
                appendix.append(summary.trim()).append("\n\n");
            }

            List<String> facts = new ArrayList<>(2);
            Object pageNo = chunk.getMetadata() == null ? null : chunk.getMetadata().get("page_no");
            if (pageNo != null) {
                facts.add("页码：" + pageNo);
            }
            if (chunk.getScore() != null) {
                facts.add("匹配分：" + String.format("%.3f", chunk.getScore()));
            }
            if (!facts.isEmpty()) {
                appendix.append(String.join("  \n", facts)).append("\n\n");
            }
            index++;
        }
        return index == 1 ? "" : appendix.toString().trim();
    }

    private String extractImageUri(RetrievedChunk chunk) {
        if (chunk == null || chunk.getMetadata() == null) {
            return null;
        }
        Object imageUri = chunk.getMetadata().get("image_uri");
        return imageUri == null ? null : String.valueOf(imageUri);
    }

    private String extractSummary(RetrievedChunk chunk) {
        if (chunk == null) {
            return null;
        }
        if (chunk.getMetadata() != null) {
            Object summary = chunk.getMetadata().get("summary");
            if (summary != null && StrUtil.isNotBlank(String.valueOf(summary))) {
                return String.valueOf(summary);
            }
        }
        return chunk.getText();
    }

    private String buildAltText(RetrievedChunk chunk, int index) {
        String summary = extractSummary(chunk);
        if (StrUtil.isBlank(summary)) {
            return "知识库相关图片 " + index;
        }
        String compact = summary.replaceAll("\\s+", " ").trim();
        if (compact.length() <= 48) {
            return compact;
        }
        return compact.substring(0, 48);
    }
}
