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

package com.nageoffer.ai.ragent.rag.core.prompt;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DefaultContextFormatter implements ContextFormatter {

    @Override
    public String formatKbContext(List<NodeScore> kbIntents, Map<String, List<RetrievedChunk>> rerankedByIntent, int topK) {
        if (rerankedByIntent == null || rerankedByIntent.isEmpty()) {
            return "";
        }
        if (CollUtil.isEmpty(kbIntents)) {
            return formatChunksWithoutIntent(rerankedByIntent, topK);
        }
        if (kbIntents.size() > 1) {
            return formatMultiIntentContext(kbIntents, rerankedByIntent, topK);
        }
        return formatSingleIntentContext(kbIntents.get(0), rerankedByIntent, topK);
    }

    private String formatSingleIntentContext(NodeScore nodeScore, Map<String, List<RetrievedChunk>> rerankedByIntent, int topK) {
        List<RetrievedChunk> chunks = rerankedByIntent.get(nodeScore.getNode().getId());
        if (CollUtil.isEmpty(chunks)) {
            return "";
        }
        String snippet = StrUtil.emptyIfNull(nodeScore.getNode().getPromptSnippet()).trim();
        StringBuilder block = new StringBuilder();
        if (StrUtil.isNotBlank(snippet)) {
            block.append("#### 回答规则\n").append(snippet).append("\n\n");
        }
        block.append(formatChunkGroups(chunks, topK));
        return block.toString().trim();
    }

    private String formatMultiIntentContext(List<NodeScore> kbIntents, Map<String, List<RetrievedChunk>> rerankedByIntent, int topK) {
        StringBuilder result = new StringBuilder();

        List<String> snippets = kbIntents.stream()
                .map(ns -> ns.getNode().getPromptSnippet())
                .filter(StrUtil::isNotBlank)
                .map(String::trim)
                .distinct()
                .toList();

        if (!snippets.isEmpty()) {
            result.append("#### 回答规则\n");
            for (int i = 0; i < snippets.size(); i++) {
                result.append(i + 1).append(". ").append(snippets.get(i)).append("\n");
            }
            result.append("\n");
        }

        List<RetrievedChunk> allChunks = rerankedByIntent.values().stream()
                .flatMap(List::stream)
                .distinct()
                .limit(topK)
                .toList();

        if (!allChunks.isEmpty()) {
            result.append(formatChunkGroups(allChunks, topK));
        }
        return result.toString().trim();
    }

    private String formatChunksWithoutIntent(Map<String, List<RetrievedChunk>> rerankedByIntent, int topK) {
        int limit = topK > 0 ? topK : Integer.MAX_VALUE;
        List<RetrievedChunk> chunks = new ArrayList<>();
        for (List<RetrievedChunk> list : rerankedByIntent.values()) {
            if (CollUtil.isEmpty(list)) {
                continue;
            }
            for (RetrievedChunk chunk : list) {
                chunks.add(chunk);
                if (chunks.size() >= limit) {
                    break;
                }
            }
            if (chunks.size() >= limit) {
                break;
            }
        }
        if (chunks.isEmpty()) {
            return "";
        }
        return formatChunkGroups(chunks, topK);
    }

    private String formatChunkGroups(List<RetrievedChunk> chunks, int topK) {
        List<RetrievedChunk> limited = chunks.stream()
                .limit(topK > 0 ? topK : chunks.size())
                .toList();
        List<RetrievedChunk> textChunks = limited.stream().filter(chunk -> !chunk.isVisual()).toList();
        List<RetrievedChunk> visualChunks = limited.stream().filter(RetrievedChunk::isVisual).toList();

        StringBuilder block = new StringBuilder();
        if (!textChunks.isEmpty()) {
            String body = textChunks.stream()
                    .map(RetrievedChunk::getText)
                    .collect(Collectors.joining("\n"));
            block.append("#### 知识库片段\n````text\n").append(body).append("\n````");
        }
        if (!visualChunks.isEmpty()) {
            if (block.length() > 0) {
                block.append("\n\n");
            }
            block.append("#### 图片证据\n");
            for (RetrievedChunk visualChunk : visualChunks) {
                block.append(formatVisualChunk(visualChunk)).append("\n");
            }
        }
        return block.toString().trim();
    }

    private String formatVisualChunk(RetrievedChunk chunk) {
        Map<String, Object> metadata = chunk.getMetadata() == null ? Map.of() : chunk.getMetadata();
        Object blockType = metadata.get("block_type");
        Object pageNo = metadata.get("page_no");
        Object imageUri = metadata.get("image_uri");
        Object summary = metadata.get("summary");
        Object bbox = metadata.get("bbox");

        StringBuilder line = new StringBuilder("- ");
        if (blockType != null) {
            line.append("type=").append(blockType).append("; ");
        }
        if (pageNo != null) {
            line.append("page=").append(pageNo).append("; ");
        }
        if (imageUri != null) {
            line.append("image=").append(imageUri).append("; ");
        }
        if (bbox != null) {
            line.append("bbox=").append(bbox).append("; ");
        }

        String description = summary != null ? String.valueOf(summary) : chunk.getText();
        line.append("desc=").append(StrUtil.emptyIfNull(description));
        return line.toString();
    }

    @Override
    public String formatMcpContext(List<MCPResponse> responses, List<NodeScore> mcpIntents) {
        if (CollUtil.isEmpty(responses) || responses.stream().noneMatch(MCPResponse::isSuccess)) {
            return "";
        }
        if (CollUtil.isEmpty(mcpIntents)) {
            return mergeResponsesToText(responses);
        }

        Map<String, IntentNode> toolToIntent = new LinkedHashMap<>();
        for (NodeScore ns : mcpIntents) {
            IntentNode node = ns.getNode();
            if (node == null || StrUtil.isBlank(node.getMcpToolId())) {
                continue;
            }
            toolToIntent.putIfAbsent(node.getMcpToolId(), node);
        }

        Map<String, List<MCPResponse>> grouped = responses.stream()
                .filter(MCPResponse::isSuccess)
                .filter(r -> StrUtil.isNotBlank(r.getToolId()))
                .collect(Collectors.groupingBy(MCPResponse::getToolId));

        return toolToIntent.entrySet().stream()
                .map(entry -> {
                    List<MCPResponse> toolResponses = grouped.get(entry.getKey());
                    if (CollUtil.isEmpty(toolResponses)) {
                        return "";
                    }
                    IntentNode node = entry.getValue();
                    String snippet = StrUtil.emptyIfNull(node.getPromptSnippet()).trim();
                    String body = mergeResponsesToText(toolResponses);
                    if (StrUtil.isBlank(body)) {
                        return "";
                    }
                    StringBuilder block = new StringBuilder();
                    if (StrUtil.isNotBlank(snippet)) {
                        block.append("#### 意图规则\n").append(snippet).append("\n");
                    }
                    block.append("#### 动态数据片段\n").append(body);
                    return block.toString();
                })
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.joining("\n\n"));
    }

    private String mergeResponsesToText(List<MCPResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return "";
        }

        List<String> successResults = new ArrayList<>();
        List<String> errorResults = new ArrayList<>();

        for (MCPResponse response : responses) {
            if (response.isSuccess() && response.getTextResult() != null) {
                successResults.add(response.getTextResult());
            } else if (!response.isSuccess()) {
                errorResults.add(String.format("工具 %s 调用失败: %s", response.getToolId(), response.getErrorMessage()));
            }
        }

        StringBuilder sb = new StringBuilder();
        if (!successResults.isEmpty()) {
            for (String result : successResults) {
                sb.append(result).append("\n\n");
            }
        }
        if (!errorResults.isEmpty()) {
            sb.append("【部分查询失败】\n");
            for (String error : errorResults) {
                sb.append("- ").append(error).append("\n");
            }
        }
        return sb.toString().trim();
    }
}
