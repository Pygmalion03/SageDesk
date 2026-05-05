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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class VisualAnswerAppendixService {

    private static final Pattern IMAGE_REFERENCE_PATTERN = Pattern.compile("(?is)(<img\\b[^>]*>|!\\[[^]]*]\\([^)]*\\))");

    private final MediaPreviewService mediaPreviewService;
    private final RAGDefaultProperties ragDefaultProperties;

    private record AppendixImage(RetrievedChunk chunk, String previewUrl) {
    }

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
                    String imageUri = extractAppendixImageUri(chunk);
                    if (StrUtil.isBlank(imageUri)) {
                        return;
                    }
                    uniqueChunks.putIfAbsent(buildVisualDedupeKey(chunk, imageUri), chunk);
                });

        if (uniqueChunks.isEmpty()) {
            return "";
        }

        List<RetrievedChunk> selectedChunks = selectAppendixChunks(uniqueChunks.values(), imageLimit);
        if (selectedChunks.isEmpty()) {
            return "";
        }

        List<AppendixImage> appendixImages = selectedChunks.stream()
                .map(chunk -> new AppendixImage(chunk, mediaPreviewService.buildPreviewUrl(extractAppendixImageUri(chunk))))
                .filter(image -> StrUtil.isNotBlank(image.previewUrl()))
                .toList();
        if (appendixImages.isEmpty()) {
            return "";
        }

        StringBuilder appendix = new StringBuilder("\n\n---\n**相关图片参考**（共")
                .append(appendixImages.size())
                .append("张）\n");
        int index = 1;
        for (AppendixImage image : appendixImages) {
            RetrievedChunk chunk = image.chunk();
            appendix.append("\n### 图片 ").append(index).append("\n\n");
            appendix.append("![")
                    .append(buildAltText(chunk, index).replace("[", " ").replace("]", " "))
                    .append("](<")
                    .append(image.previewUrl())
                    .append(">)\n\n");

            String quote = buildReferenceQuote(chunk);
            if (StrUtil.isNotBlank(quote)) {
                appendix.append("> ").append(quote).append("\n\n");
            }
            index++;
        }
        return index == 1 ? "" : appendix.toString().trim();
    }

    private List<RetrievedChunk> selectAppendixChunks(Collection<RetrievedChunk> chunks, int imageLimit) {
        List<RetrievedChunk> candidates = new ArrayList<>(chunks);
        candidates = deduplicateSamePageWithRichEvidence(candidates);
        candidates = filterDebugOverlayImages(candidates);
        candidates = filterVeryLowScoreVisuals(candidates);
        candidates = filterLowScoreVisualOutliers(candidates);
        candidates = filterGenericCategoryWhenSpecificProductPageExists(candidates);
        boolean hasRichPageEvidence = candidates.stream().anyMatch(this::isRichPageEvidence);
        if (hasRichPageEvidence) {
            candidates = candidates.stream()
                    .filter(chunk -> isRichPageEvidence(chunk) || !isSimpleImageCrop(chunk))
                    .toList();
        }

        candidates = new ArrayList<>(candidates);
        candidates.sort((left, right) -> {
            int priority = Integer.compare(visualPriority(left), visualPriority(right));
            if (priority != 0) {
                return priority;
            }
            return Float.compare(scoreValue(right), scoreValue(left));
        });
        return candidates.stream()
                .limit(imageLimit)
                .toList();
    }

    private List<RetrievedChunk> deduplicateSamePageWithRichEvidence(List<RetrievedChunk> candidates) {
        Map<String, RetrievedChunk> bestRichByPage = new LinkedHashMap<>();
        for (RetrievedChunk chunk : candidates) {
            if (!isRichPageEvidence(chunk)) {
                continue;
            }
            String pageKey = pageDedupeKey(chunk);
            if (StrUtil.isBlank(pageKey)) {
                continue;
            }
            RetrievedChunk existing = bestRichByPage.get(pageKey);
            if (existing == null || preferVisualEvidence(chunk, existing)) {
                bestRichByPage.put(pageKey, chunk);
            }
        }
        if (bestRichByPage.isEmpty()) {
            return candidates;
        }

        return candidates.stream()
                .filter(chunk -> {
                    String pageKey = pageDedupeKey(chunk);
                    if (StrUtil.isBlank(pageKey) || !bestRichByPage.containsKey(pageKey)) {
                        return true;
                    }
                    return bestRichByPage.get(pageKey) == chunk;
                })
                .toList();
    }

    private List<RetrievedChunk> filterDebugOverlayImages(List<RetrievedChunk> candidates) {
        List<RetrievedChunk> filtered = candidates.stream()
                .filter(chunk -> !isDebugOverlayImage(chunk))
                .toList();
        return filtered;
    }

    private List<RetrievedChunk> filterVeryLowScoreVisuals(List<RetrievedChunk> candidates) {
        float minScore = ragDefaultProperties.getVisualAnswerMinScore() == null
                ? 0.05f
                : Math.max(ragDefaultProperties.getVisualAnswerMinScore(), 0f);
        if (minScore <= 0f) {
            return candidates;
        }
        return candidates.stream()
                .filter(chunk -> chunk.getScore() == null || scoreValue(chunk) >= minScore)
                .toList();
    }

    private List<RetrievedChunk> filterLowScoreVisualOutliers(List<RetrievedChunk> candidates) {
        if (candidates.size() <= 1) {
            return candidates;
        }
        float bestScore = candidates.stream()
                .map(this::scoreValue)
                .max(Float::compare)
                .orElse(0f);
        if (bestScore < 0.3f) {
            return candidates;
        }

        float threshold = Math.max(0.3f, bestScore * 0.5f);
        List<RetrievedChunk> filtered = candidates.stream()
                .filter(chunk -> scoreValue(chunk) >= threshold)
                .toList();
        return filtered.isEmpty() ? candidates : filtered;
    }

    private List<RetrievedChunk> filterGenericCategoryWhenSpecificProductPageExists(List<RetrievedChunk> candidates) {
        boolean hasSpecificProductPage = candidates.stream()
                .anyMatch(this::isSpecificProductPageEvidence);
        if (!hasSpecificProductPage) {
            return candidates;
        }
        List<RetrievedChunk> filtered = candidates.stream()
                .filter(chunk -> !isGenericCategoryOverview(chunk))
                .toList();
        return filtered.isEmpty() ? candidates : filtered;
    }

    private String extractImageUri(RetrievedChunk chunk) {
        if (chunk == null || chunk.getMetadata() == null) {
            return null;
        }
        Object imageUri = chunk.getMetadata().get("image_uri");
        return imageUri == null ? null : String.valueOf(imageUri);
    }

    private String extractAppendixImageUri(RetrievedChunk chunk) {
        String imageUri = extractImageUri(chunk);
        if (!isLocalCropImage(imageUri)) {
            return imageUri;
        }

        String sourceLocation = metadataText(chunk, "source_location");
        if (isPreviewableImageUri(sourceLocation)) {
            return sourceLocation;
        }
        String sourcePageImage = metadataText(chunk, "source_page_image");
        if (isPreviewableImageUri(sourcePageImage)) {
            return sourcePageImage;
        }
        return imageUri;
    }

    private String extractSummary(RetrievedChunk chunk) {
        if (chunk == null) {
            return null;
        }
        if (isRichPageEvidence(chunk)) {
            return cleanSummary(chunk.getText(), 420);
        }
        String sourceImageName = sourceImageName(chunk, extractImageUri(chunk));
        String focusedSummary = extractFocusedSummary(chunk.getText(), sourceImageName);
        if (StrUtil.isNotBlank(focusedSummary)) {
            return cleanSummary(focusedSummary, 220);
        }
        if (chunk.getMetadata() != null) {
            Object summary = chunk.getMetadata().get("summary");
            if (summary != null && StrUtil.isNotBlank(String.valueOf(summary))) {
                return cleanSummary(String.valueOf(summary), 320);
            }
        }
        return cleanSummary(chunk.getText(), 320);
    }

    private String buildReferenceQuote(RetrievedChunk chunk) {
        List<String> parts = new ArrayList<>(2);
        String summary = extractSummaryExcerpt(chunk);
        if (StrUtil.isNotBlank(summary)) {
            parts.add(summary);
        }

        List<String> facts = new ArrayList<>(2);
        Object pageNo = chunk.getMetadata() == null ? null : chunk.getMetadata().get("page_no");
        if (pageNo != null) {
            facts.add("页码: " + pageNo);
        }
        if (chunk.getScore() != null) {
            facts.add("相似度: " + String.format("%.3f", chunk.getScore()));
        }
        if (!facts.isEmpty()) {
            parts.add(String.join(" | ", facts));
        }
        return String.join("  ", parts);
    }

    private String extractSummaryExcerpt(RetrievedChunk chunk) {
        if (chunk == null) {
            return null;
        }
        if (chunk.getMetadata() != null) {
            Object summary = chunk.getMetadata().get("summary");
            if (summary != null && StrUtil.isNotBlank(String.valueOf(summary))) {
                return cleanSummary(String.valueOf(summary), 200);
            }
        }
        return extractSummary(chunk);
    }

    private String cleanSummary(String raw, int maxLength) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        String cleaned = raw
                .replace("\\n", "\n")
                .replaceAll("(?is)<img\\b[^>]*>", " ")
                .replaceAll("(?is)<[^>]+>", " ")
                .replaceAll("!\\[[^]]*]\\([^)]*\\)", " ")
                .replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "")
                .replaceAll("#{2,6}\\s*", " ")
                .replaceAll("\\p{So}", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.length() <= maxLength) {
            return cleaned;
        }
        return cleaned.substring(0, maxLength) + "...";
    }

    private String extractFocusedSummary(String text, String sourceImageName) {
        if (StrUtil.isBlank(text) || StrUtil.isBlank(sourceImageName)) {
            return null;
        }
        int imageIndex = text.indexOf(sourceImageName);
        if (imageIndex < 0) {
            return null;
        }
        int imageTagEnd = text.indexOf('>', imageIndex);
        int markdownImageEnd = text.indexOf(')', imageIndex);
        int start = imageIndex + sourceImageName.length();
        if (imageTagEnd >= imageIndex) {
            start = imageTagEnd + 1;
        } else if (markdownImageEnd >= imageIndex) {
            start = markdownImageEnd + 1;
        }
        String tail = text.substring(start);
        Matcher nextImage = IMAGE_REFERENCE_PATTERN.matcher(tail);
        if (nextImage.find()) {
            tail = tail.substring(0, nextImage.start());
        }
        return tail;
    }

    private String buildVisualDedupeKey(RetrievedChunk chunk, String imageUri) {
        String sourceImageName = sourceImageName(chunk, imageUri);
        String scope = metadataText(chunk, "task_id");
        if (StrUtil.isBlank(scope)) {
            scope = metadataText(chunk, "source_location");
        }
        if (StrUtil.isNotBlank(sourceImageName)) {
            return "source:" + StrUtil.blankToDefault(scope, "") + ":" + sourceImageName;
        }

        String blockId = metadataText(chunk, "block_id");
        if (StrUtil.isNotBlank(blockId)) {
            return "block:" + StrUtil.blankToDefault(scope, "") + ":" + blockId;
        }

        String pageNo = metadataText(chunk, "page_no");
        if (StrUtil.isNotBlank(pageNo) && StrUtil.isNotBlank(scope)) {
            return "page:" + scope + ":" + pageNo;
        }
        return "uri:" + imageUri;
    }

    private String pageDedupeKey(RetrievedChunk chunk) {
        String pageNo = metadataText(chunk, "page_no");
        if (StrUtil.isBlank(pageNo)) {
            return null;
        }
        String scope = metadataText(chunk, "task_id");
        if (StrUtil.isBlank(scope)) {
            scope = metadataText(chunk, "source_location");
        }
        return "page:" + StrUtil.blankToDefault(scope, "") + ":" + pageNo;
    }

    private int visualPriority(RetrievedChunk chunk) {
        if (isDebugOverlayImage(chunk)) {
            return 3;
        }
        if (isRichPageEvidence(chunk)) {
            return 0;
        }
        return isSimpleImageCrop(chunk) ? 2 : 1;
    }

    private boolean preferVisualEvidence(RetrievedChunk candidate, RetrievedChunk existing) {
        int priority = Integer.compare(visualPriority(candidate), visualPriority(existing));
        if (priority != 0) {
            return priority < 0;
        }
        return scoreValue(candidate) > scoreValue(existing);
    }

    private boolean isDebugOverlayImage(RetrievedChunk chunk) {
        String imageUri = StrUtil.emptyIfNull(extractImageUri(chunk)).replace('\\', '/');
        String sourceImageName = StrUtil.emptyIfNull(sourceImageName(chunk, extractImageUri(chunk))).replace('\\', '/');
        String combined = (imageUri + " " + sourceImageName).toLowerCase(Locale.ROOT);
        return combined.contains("layout_det_res")
                || combined.contains("layout_res")
                || combined.contains("det_res.jpg")
                || combined.contains("ocr_res")
                || combined.contains("vis_result")
                || combined.contains("_annotated")
                || (combined.contains("paddle_api_runtime") && combined.contains("/output/"))
                || (combined.contains("paddle_bridge_runtime") && combined.contains("/output/"));
    }

    private boolean isSimpleImageCrop(RetrievedChunk chunk) {
        if (isLocalCropImage(extractImageUri(chunk))) {
            return true;
        }
        String sourceImageName = sourceImageName(chunk, extractImageUri(chunk));
        if (StrUtil.isBlank(sourceImageName)) {
            return false;
        }
        return sourceImageName.toLowerCase(Locale.ROOT).contains("img_in_image_box")
                && !isRichPageEvidence(chunk);
    }

    private boolean isRichPageEvidence(RetrievedChunk chunk) {
        if (chunk == null) {
            return false;
        }
        String raw = textWithSummary(chunk);
        if (StrUtil.isBlank(raw)) {
            return false;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.contains("technical specifications")
                || lower.contains("feed width")
                || lower.contains("price usd")
                || lower.contains("paper capacity")
                || lower.contains("disc capacity")
                || raw.contains("主要参数")
                || raw.contains("入口参数")
                || raw.contains("价格")
                || raw.contains("型号");
    }

    private boolean isSpecificProductPageEvidence(RetrievedChunk chunk) {
        String raw = textWithSummary(chunk);
        if (StrUtil.isBlank(raw)) {
            return false;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        boolean productSeriesPage = lower.contains("yd-338cc series")
                || lower.contains("yd338cc series");
        boolean detailedSpecificationTable = lower.contains("technical specifications")
                && (lower.contains("feed width")
                || lower.contains("paper capacity")
                || lower.contains("disc capacity")
                || lower.contains("price usd"));
        return productSeriesPage || detailedSpecificationTable;
    }

    private boolean isGenericCategoryOverview(RetrievedChunk chunk) {
        String raw = textWithSummary(chunk);
        if (StrUtil.isBlank(raw)) {
            return false;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        boolean categoryOverview = lower.contains("high-power office shredders")
                || lower.contains("representative model")
                || lower.contains("representative models")
                || lower.contains("featured models")
                || lower.contains("overview suitable for")
                || raw.contains("大功率办公碎纸机")
                || raw.contains("大型办公系列")
                || raw.contains("代表型号");
        return categoryOverview && !isSpecificProductPageEvidence(chunk);
    }

    private String textWithSummary(RetrievedChunk chunk) {
        if (chunk == null) {
            return "";
        }
        String raw = StrUtil.emptyIfNull(chunk.getText());
        if (chunk.getMetadata() != null) {
            Object summary = chunk.getMetadata().get("summary");
            if (summary != null) {
                raw += " " + summary;
            }
        }
        return raw;
    }

    private float scoreValue(RetrievedChunk chunk) {
        return chunk == null || chunk.getScore() == null ? 0f : chunk.getScore();
    }

    private String sourceImageName(RetrievedChunk chunk, String imageUri) {
        String explicit = metadataText(chunk, "source_image_name");
        if (StrUtil.isNotBlank(explicit)) {
            return explicit;
        }
        explicit = metadataText(chunk, "selected_image_name");
        if (StrUtil.isNotBlank(explicit)) {
            return explicit;
        }
        if (chunk == null || chunk.getMetadata() == null) {
            return null;
        }
        Object markdownImages = chunk.getMetadata().get("markdown_images");
        if (!(markdownImages instanceof Map<?, ?> images)) {
            return null;
        }
        for (Map.Entry<?, ?> entry : images.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = String.valueOf(entry.getKey());
            String value = String.valueOf(entry.getValue());
            if (Objects.equals(value, imageUri)) {
                return key;
            }
        }
        return null;
    }

    private String metadataText(RetrievedChunk chunk, String key) {
        if (chunk == null || chunk.getMetadata() == null) {
            return null;
        }
        Object value = chunk.getMetadata().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private boolean isLocalCropImage(String imageUri) {
        if (StrUtil.isBlank(imageUri)) {
            return false;
        }
        String normalized = imageUri.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.contains("/paddle_bridge_runtime/crops/")
                || normalized.contains("/paddle_api_runtime/crops/")
                || normalized.contains("/crops/");
    }

    private boolean isPreviewableImageUri(String imageUri) {
        if (StrUtil.isBlank(imageUri)) {
            return false;
        }
        String normalized = imageUri.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("s3://")
                || normalized.startsWith("http://")
                || normalized.startsWith("https://")
                || normalized.startsWith("file:/")
                || normalized.endsWith(".png")
                || normalized.endsWith(".jpg")
                || normalized.endsWith(".jpeg")
                || normalized.endsWith(".webp")
                || normalized.endsWith(".gif")
                || normalized.endsWith(".bmp");
    }

    private String buildAltText(RetrievedChunk chunk, int index) {
        String summary = extractSummaryExcerpt(chunk);
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
