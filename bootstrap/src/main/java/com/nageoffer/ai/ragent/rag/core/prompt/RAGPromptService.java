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
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MCP_KB_MIXED_PROMPT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MCP_ONLY_PROMPT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.RAG_ENTERPRISE_PROMPT_PATH;

/**
 * RAG Prompt 编排服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGPromptService {

    private static final String MCP_CONTEXT_HEADER = "## 动态数据片段";
    private static final String KB_CONTEXT_HEADER = "## 文档内容";
    private static final String VISUAL_GUIDANCE_HEADER = "## 图片使用说明";

    private final PromptTemplateLoader promptTemplateLoader;
    private final RAGDefaultProperties ragDefaultProperties;

    public String buildSystemPrompt(PromptContext context) {
        PromptBuildPlan plan = plan(context);
        String template = StrUtil.isNotBlank(plan.getBaseTemplate())
                ? plan.getBaseTemplate()
                : defaultTemplate(plan.getScene());
        return StrUtil.isBlank(template) ? "" : PromptTemplateUtils.cleanupPrompt(template);
    }

    public List<ChatMessage> buildStructuredMessages(PromptContext context,
                                                     List<ChatMessage> history,
                                                     String question,
                                                     List<String> subQuestions) {
        List<ChatMessage> messages = new ArrayList<>();
        String systemPrompt = buildSystemPrompt(context);
        if (StrUtil.isNotBlank(systemPrompt)) {
            messages.add(ChatMessage.system(systemPrompt));
        }
        if (StrUtil.isNotBlank(context.getMcpContext())) {
            messages.add(ChatMessage.system(formatEvidence(MCP_CONTEXT_HEADER, context.getMcpContext())));
        }
        if (CollUtil.isNotEmpty(history)) {
            messages.addAll(history);
        }

        List<RetrievedChunk> visualChunks = context.visualChunks();
        String userPrompt = buildUserPrompt(context, question, subQuestions, !visualChunks.isEmpty());
        ChatMessage userMessage = buildUserMessage(userPrompt, visualChunks);
        messages.add(userMessage);
        return messages;
    }

    private ChatMessage buildUserMessage(String userPrompt, List<RetrievedChunk> visualChunks) {
        List<ChatMessage.ContentPart> parts = new ArrayList<>();
        parts.add(ChatMessage.ContentPart.text(userPrompt));

        LinkedHashSet<String> imagePayloads = new LinkedHashSet<>();
        int imageLimit = ragDefaultProperties.getVisualAnswerImageLimit() == null
                ? 4
                : Math.max(ragDefaultProperties.getVisualAnswerImageLimit(), 0);
        for (RetrievedChunk visualChunk : visualChunks) {
            if (imagePayloads.size() >= imageLimit) {
                break;
            }
            String imageUri = extractImageUri(visualChunk);
            String payload = resolveImagePayload(imageUri);
            if (StrUtil.isNotBlank(payload)) {
                imagePayloads.add(payload);
            }
        }

        if (imagePayloads.isEmpty()) {
            return ChatMessage.user(userPrompt);
        }

        imagePayloads.forEach(image -> parts.add(ChatMessage.ContentPart.imageUrl(image)));
        return ChatMessage.userParts(parts);
    }

    private String buildUserPrompt(PromptContext context,
                                   String question,
                                   List<String> subQuestions,
                                   boolean hasVisualInputs) {
        StringBuilder prompt = new StringBuilder();
        if (StrUtil.isNotBlank(context.getKbContext())) {
            prompt.append(formatEvidence(KB_CONTEXT_HEADER, context.getKbContext())).append("\n\n");
        }
        if (hasVisualInputs) {
            prompt.append(VISUAL_GUIDANCE_HEADER).append("\n")
                    .append("已附上召回图片。回答时请同时参考图片和文本证据；如果图片内容与 OCR/摘要冲突，以图片本身为准。")
                    .append("\n\n");
        }
        prompt.append("## 用户问题\n");
        if (CollUtil.isNotEmpty(subQuestions) && subQuestions.size() > 1) {
            prompt.append("请逐项回答下面的问题：\n");
            for (int i = 0; i < subQuestions.size(); i++) {
                prompt.append(i + 1).append(". ").append(subQuestions.get(i)).append("\n");
            }
        } else {
            prompt.append(StrUtil.blankToDefault(question, context.getQuestion()));
        }
        return prompt.toString().trim();
    }

    private String resolveImagePayload(String imageUri) {
        if (StrUtil.isBlank(imageUri)) {
            return null;
        }
        String trimmed = imageUri.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("data:")) {
            return trimmed;
        }
        try {
            Path path = trimmed.startsWith("file:/")
                    ? Paths.get(URI.create(trimmed))
                    : Paths.get(trimmed);
            if (!Files.exists(path) || Files.isDirectory(path)) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(path);
            String mimeType = Files.probeContentType(path);
            if (StrUtil.isBlank(mimeType)) {
                mimeType = inferMimeType(path);
            }
            if (StrUtil.isBlank(mimeType)) {
                mimeType = "application/octet-stream";
            }
            return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (Exception ex) {
            log.debug("Resolve multimodal image payload failed, imageUri={}", trimmed, ex);
            return null;
        }
    }

    private String inferMimeType(Path path) {
        if (path == null || path.getFileName() == null) {
            return null;
        }
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        if (name.endsWith(".bmp")) {
            return "image/bmp";
        }
        return null;
    }

    private String extractImageUri(RetrievedChunk visualChunk) {
        if (visualChunk == null || visualChunk.getMetadata() == null) {
            return null;
        }
        Object imageUri = visualChunk.getMetadata().get("image_uri");
        return imageUri == null ? null : String.valueOf(imageUri);
    }

    private PromptPlan planPrompt(List<NodeScore> intents, Map<String, List<RetrievedChunk>> intentChunks) {
        List<NodeScore> safeIntents = intents == null ? Collections.emptyList() : intents;

        List<NodeScore> retained = safeIntents.stream()
                .filter(ns -> {
                    IntentNode node = ns.getNode();
                    String key = nodeKey(node);
                    List<RetrievedChunk> chunks = intentChunks == null ? null : intentChunks.get(key);
                    return CollUtil.isNotEmpty(chunks);
                })
                .toList();

        if (retained.isEmpty()) {
            return new PromptPlan(Collections.emptyList(), null);
        }

        if (retained.size() == 1) {
            IntentNode only = retained.get(0).getNode();
            String tpl = StrUtil.emptyIfNull(only.getPromptTemplate()).trim();
            return StrUtil.isNotBlank(tpl)
                    ? new PromptPlan(retained, tpl)
                    : new PromptPlan(retained, null);
        }
        return new PromptPlan(retained, null);
    }

    private PromptBuildPlan plan(PromptContext context) {
        if (context.hasMcp() && !context.hasKb()) {
            return planMcpOnly(context);
        }
        if (!context.hasMcp() && context.hasKb()) {
            return planKbOnly(context);
        }
        if (context.hasMcp() && context.hasKb()) {
            return planMixed(context);
        }
        throw new IllegalStateException("PromptContext requires MCP or KB context.");
    }

    private PromptBuildPlan planKbOnly(PromptContext context) {
        PromptPlan plan = planPrompt(context.getKbIntents(), context.getIntentChunks());
        return PromptBuildPlan.builder()
                .scene(PromptScene.KB_ONLY)
                .baseTemplate(plan.getBaseTemplate())
                .mcpContext(context.getMcpContext())
                .kbContext(context.getKbContext())
                .question(context.getQuestion())
                .build();
    }

    private PromptBuildPlan planMcpOnly(PromptContext context) {
        List<NodeScore> intents = context.getMcpIntents();
        String baseTemplate = null;
        if (CollUtil.isNotEmpty(intents) && intents.size() == 1) {
            IntentNode node = intents.get(0).getNode();
            String tpl = StrUtil.emptyIfNull(node.getPromptTemplate()).trim();
            if (StrUtil.isNotBlank(tpl)) {
                baseTemplate = tpl;
            }
        }

        return PromptBuildPlan.builder()
                .scene(PromptScene.MCP_ONLY)
                .baseTemplate(baseTemplate)
                .mcpContext(context.getMcpContext())
                .kbContext(context.getKbContext())
                .question(context.getQuestion())
                .build();
    }

    private PromptBuildPlan planMixed(PromptContext context) {
        return PromptBuildPlan.builder()
                .scene(PromptScene.MIXED)
                .mcpContext(context.getMcpContext())
                .kbContext(context.getKbContext())
                .question(context.getQuestion())
                .build();
    }

    private String defaultTemplate(PromptScene scene) {
        return switch (scene) {
            case KB_ONLY -> promptTemplateLoader.load(RAG_ENTERPRISE_PROMPT_PATH);
            case MCP_ONLY -> promptTemplateLoader.load(MCP_ONLY_PROMPT_PATH);
            case MIXED -> promptTemplateLoader.load(MCP_KB_MIXED_PROMPT_PATH);
            case EMPTY -> "";
        };
    }

    private String formatEvidence(String header, String body) {
        return header + "\n" + body.trim();
    }

    private static String nodeKey(IntentNode node) {
        if (node == null) {
            return "";
        }
        if (StrUtil.isNotBlank(node.getId())) {
            return node.getId();
        }
        return String.valueOf(node.getId());
    }
}
