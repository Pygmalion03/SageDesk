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

package com.nageoffer.ai.ragent.ingestion.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.parser.DocumentParser;
import com.nageoffer.ai.ragent.core.parser.DocumentParserSelector;
import com.nageoffer.ai.ragent.core.parser.ParseResult;
import com.nageoffer.ai.ragent.core.parser.ParserType;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.context.StructuredDocument;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.ingestion.domain.settings.ParserSettings;
import com.nageoffer.ai.ragent.ingestion.util.MimeTypeDetector;
import com.nageoffer.ai.ragent.rag.config.DocumentAnalysisProperties;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ParserNode implements IngestionNode {

    private final ObjectMapper objectMapper;
    private final DocumentParserSelector parserSelector;
    private final DocumentAnalysisProperties documentAnalysisProperties;
    private final RAGDefaultProperties ragDefaultProperties;

    public ParserNode(ObjectMapper objectMapper,
                      DocumentParserSelector parserSelector,
                      DocumentAnalysisProperties documentAnalysisProperties,
                      RAGDefaultProperties ragDefaultProperties) {
        this.objectMapper = objectMapper;
        this.parserSelector = parserSelector;
        this.documentAnalysisProperties = documentAnalysisProperties;
        this.ragDefaultProperties = ragDefaultProperties;
    }

    @Override
    public String getNodeType() {
        return IngestionNodeType.PARSER.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        if (context.getRawBytes() == null || context.getRawBytes().length == 0) {
            return NodeResult.fail(new ClientException("Parser node requires raw document bytes"));
        }

        String mimeType = context.getMimeType();
        if (!StringUtils.hasText(mimeType)) {
            String fileName = context.getSource() == null ? null : context.getSource().getFileName();
            mimeType = MimeTypeDetector.detect(context.getRawBytes(), fileName);
            context.setMimeType(mimeType);
        }

        ParserSettings settings = parseSettings(config.getSettings());
        String fileName = context.getSource() == null ? null : context.getSource().getFileName();
        validateMimeType(settings, mimeType, fileName);

        ParserSettings.ParserRule rule = matchRule(settings, mimeType, fileName);
        String parserType = resolveParserType(rule, mimeType, fileName);
        DocumentParser parser = parserSelector.select(parserType);
        if (parser == null) {
            return NodeResult.fail(new ClientException("Parser not found: " + parserType));
        }

        Map<String, Object> options = new HashMap<>();
        if (rule != null && rule.getOptions() != null) {
            options.putAll(rule.getOptions());
        }
        if (StringUtils.hasText(fileName)) {
            options.putIfAbsent("fileName", fileName);
        }
        if (context.getSource() != null && StringUtils.hasText(context.getSource().getLocation())) {
            options.putIfAbsent("sourceLocation", context.getSource().getLocation());
        }
        String storageBucket = context.getVectorSpaceId() != null
                ? context.getVectorSpaceId().getLogicalName()
                : ragDefaultProperties.getCollectionName();
        if (StringUtils.hasText(storageBucket)) {
            options.putIfAbsent("storageBucket", storageBucket);
        }
        ParseResult result;
        try {
            result = parser.parse(context.getRawBytes(), mimeType, options);
        } catch (Exception ex) {
            if (shouldFallbackToTika(parserType, mimeType, fileName)) {
                DocumentParser tikaParser = parserSelector.select(ParserType.TIKA.getType());
                if (tikaParser == null) {
                    return NodeResult.fail(new ClientException("Paddle parse failed and Tika fallback is unavailable: " + ex.getMessage()));
                }
                result = tikaParser.parse(context.getRawBytes(), mimeType, Collections.emptyMap());
            } else if (ex instanceof RuntimeException runtimeException) {
                return NodeResult.fail(runtimeException);
            } else {
                return NodeResult.fail(new ClientException("Document parse failed: " + ex.getMessage()));
            }
        }

        context.setRawText(result.text());

        StructuredDocument document = result.document() != null
                ? result.document()
                : StructuredDocument.builder()
                .text(result.text())
                .metadata(result.metadata())
                .build();
        if (!StringUtils.hasText(document.getText())) {
            document.setText(result.text());
        }
        if (document.getMetadata() == null) {
            document.setMetadata(result.metadata());
        }
        context.setDocument(document);
        context.setMetadata(mergeMetadata(context.getMetadata(), result.metadata()));

        return NodeResult.ok("Parsed text length=" + (result.text() == null ? 0 : result.text().length()));
    }

    private void validateMimeType(ParserSettings settings, String mimeType, String fileName) {
        if (settings == null || settings.getRules() == null || settings.getRules().isEmpty()) {
            return;
        }

        String resolvedType = resolveType(mimeType, fileName);
        boolean hasMatch = false;
        for (ParserSettings.ParserRule rule : settings.getRules()) {
            if (rule == null || !StringUtils.hasText(rule.getMimeType())) {
                continue;
            }
            String configured = normalizeType(rule.getMimeType());
            if (!StringUtils.hasText(configured)) {
                continue;
            }
            if ("ALL".equals(configured) || configured.equalsIgnoreCase(resolvedType)) {
                hasMatch = true;
                break;
            }
        }

        if (!hasMatch) {
            List<String> allowedTypes = settings.getRules().stream()
                    .filter(rule -> rule != null && StringUtils.hasText(rule.getMimeType()))
                    .map(rule -> normalizeType(rule.getMimeType()))
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();

            throw new ClientException(String.format(
                    "Document type is not allowed. Current=%s, allowed=%s",
                    resolvedType,
                    String.join(", ", allowedTypes)
            ));
        }
    }

    private ParserSettings parseSettings(JsonNode node) {
        if (node == null || node.isNull()) {
            return ParserSettings.builder().rules(List.of()).build();
        }
        return objectMapper.convertValue(node, ParserSettings.class);
    }

    private ParserSettings.ParserRule matchRule(ParserSettings settings, String mimeType, String fileName) {
        if (settings == null || settings.getRules() == null || settings.getRules().isEmpty()) {
            return null;
        }
        String resolvedType = resolveType(mimeType, fileName);
        for (ParserSettings.ParserRule rule : settings.getRules()) {
            if (rule == null || !StringUtils.hasText(rule.getMimeType())) {
                continue;
            }
            String configured = normalizeType(rule.getMimeType());
            if (!StringUtils.hasText(configured)) {
                continue;
            }
            if ("ALL".equals(configured) || configured.equalsIgnoreCase(resolvedType)) {
                return rule;
            }
        }
        return null;
    }

    private String resolveParserType(ParserSettings.ParserRule rule, String mimeType, String fileName) {
        if (rule != null && StringUtils.hasText(rule.getParserType())) {
            return normalizeParserType(rule.getParserType());
        }
        String resolvedType = resolveType(mimeType, fileName);
        if (documentAnalysisProperties.isEnabled()
                && documentAnalysisProperties.isAutoDetect()
                && documentAnalysisProperties.getAutoMimeTypes() != null
                && documentAnalysisProperties.getAutoMimeTypes().stream()
                .map(this::normalizeType)
                .anyMatch(resolvedType::equalsIgnoreCase)) {
            return ParserType.PADDLE_DOCUMENT_ANALYSIS.getType();
        }
        return ParserType.TIKA.getType();
    }

    private String resolveType(String mimeType, String fileName) {
        String byName = resolveTypeByName(fileName);
        if (StringUtils.hasText(byName)) {
            return byName;
        }
        if (!StringUtils.hasText(mimeType)) {
            return "UNKNOWN";
        }
        String lower = mimeType.trim().toLowerCase();
        if (lower.contains("pdf")) {
            return "PDF";
        }
        if (lower.contains("markdown")) {
            return "MARKDOWN";
        }
        if (lower.contains("word") || lower.contains("msword") || lower.contains("wordprocessingml")) {
            return "WORD";
        }
        if (lower.contains("excel") || lower.contains("spreadsheetml")) {
            return "EXCEL";
        }
        if (lower.contains("powerpoint") || lower.contains("presentation")) {
            return "PPT";
        }
        if (lower.startsWith("image/")) {
            return "IMAGE";
        }
        if (lower.startsWith("text/")) {
            return "TEXT";
        }
        return "UNKNOWN";
    }

    private String resolveTypeByName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return null;
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return "PDF";
        }
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return "MARKDOWN";
        }
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) {
            return "WORD";
        }
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) {
            return "EXCEL";
        }
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) {
            return "PPT";
        }
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp")) {
            return "IMAGE";
        }
        if (lower.endsWith(".txt")) {
            return "TEXT";
        }
        return null;
    }

    private String normalizeType(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim().toUpperCase();
        return switch (value) {
            case "*", "ALL", "DEFAULT" -> "ALL";
            case "MD", "MARKDOWN" -> "MARKDOWN";
            case "DOC", "DOCX", "WORD" -> "WORD";
            case "XLS", "XLSX", "EXCEL" -> "EXCEL";
            case "PPT", "PPTX", "POWERPOINT" -> "PPT";
            case "TXT", "TEXT" -> "TEXT";
            case "PNG", "JPG", "JPEG", "GIF", "BMP", "WEBP", "IMAGE", "IMG" -> "IMAGE";
            case "PDF" -> "PDF";
            default -> value;
        };
    }

    private String normalizeParserType(String raw) {
        if (!StringUtils.hasText(raw)) {
            return ParserType.TIKA.getType();
        }
        if (ParserType.TIKA.getType().equalsIgnoreCase(raw) || "TIKA".equalsIgnoreCase(raw)) {
            return ParserType.TIKA.getType();
        }
        if (ParserType.MARKDOWN.getType().equalsIgnoreCase(raw) || "MARKDOWN".equalsIgnoreCase(raw)) {
            return ParserType.MARKDOWN.getType();
        }
        if (ParserType.PADDLE_DOCUMENT_ANALYSIS.getType().equalsIgnoreCase(raw)
                || "PADDLE".equalsIgnoreCase(raw)
                || "PADDLE_DOCUMENT_ANALYSIS".equalsIgnoreCase(raw)) {
            return ParserType.PADDLE_DOCUMENT_ANALYSIS.getType();
        }
        return raw;
    }

    private boolean shouldFallbackToTika(String parserType, String mimeType, String fileName) {
        return ParserType.PADDLE_DOCUMENT_ANALYSIS.getType().equalsIgnoreCase(parserType)
                && documentAnalysisProperties.isFallbackToTikaOnError()
                && !"IMAGE".equalsIgnoreCase(resolveType(mimeType, fileName));
    }

    private Map<String, Object> mergeMetadata(Map<String, Object> existing, Map<String, Object> parsed) {
        if ((existing == null || existing.isEmpty()) && (parsed == null || parsed.isEmpty())) {
            return null;
        }
        Map<String, Object> merged = new HashMap<>();
        if (existing != null) {
            merged.putAll(existing);
        }
        if (parsed != null) {
            merged.putAll(parsed);
        }
        return merged;
    }
}
