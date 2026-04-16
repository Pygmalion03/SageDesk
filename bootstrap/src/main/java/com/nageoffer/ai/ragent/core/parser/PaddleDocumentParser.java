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

package com.nageoffer.ai.ragent.core.parser;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.ingestion.domain.context.StructuredDocument;
import com.nageoffer.ai.ragent.infra.http.HttpMediaTypes;
import com.nageoffer.ai.ragent.rag.config.DocumentAnalysisProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaddleDocumentParser implements DocumentParser {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final MediaType DEFAULT_FILE_MEDIA_TYPE = MediaType.parse("application/octet-stream");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final DocumentAnalysisProperties properties;

    @Override
    public String getParserType() {
        return ParserType.PADDLE_DOCUMENT_ANALYSIS.getType();
    }

    @Override
    public ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            return ParseResult.ofText("");
        }
        if (!properties.isEnabled()) {
            throw new ServiceException("Paddle document analysis is disabled");
        }

        String provider = resolveOption(options, "provider", properties.getProvider());
        if ("official".equalsIgnoreCase(provider)) {
            return parseOfficial(content, mimeType, options);
        }
        return parseBridge(content, mimeType, options);
    }

    @Override
    public boolean supports(String mimeType) {
        if (!StringUtils.hasText(mimeType)) {
            return false;
        }
        String value = mimeType.toLowerCase();
        return value.startsWith("image/") || value.contains("pdf");
    }

    private ParseResult parseBridge(byte[] content, String mimeType, Map<String, Object> options) {
        Map<String, Object> requestPayload = new HashMap<>();
        requestPayload.put("mimeType", mimeType);
        requestPayload.put("contentBase64", Base64.getEncoder().encodeToString(content));
        requestPayload.put("mode", resolveOption(options, "mode", properties.getDefaultMode()));
        requestPayload.put("fallbackMode", resolveOption(options, "fallbackMode", properties.getFallbackMode()));
        requestPayload.put("options", options == null ? Map.of() : options);

        Request.Builder builder = new Request.Builder()
                .url(buildBridgeUrl())
                .post(RequestBody.create(writeJson(requestPayload), HttpMediaTypes.JSON))
                .addHeader("Content-Type", HttpMediaTypes.JSON_UTF8_HEADER);
        if (StringUtils.hasText(properties.getApiKey())) {
            builder.addHeader("Authorization", "Bearer " + properties.getApiKey());
        }

        JsonNode root = executeJson(builder.build(), "Paddle document analysis");
        StructuredDocument document = parseBridgeDocument(root);
        String text = resolveBridgeText(root, document);
        Map<String, Object> metadata = parseBridgeMetadata(root, document);
        return ParseResult.of(text, metadata, document);
    }

    private ParseResult parseOfficial(byte[] content, String mimeType, Map<String, Object> options) {
        String requestMode = resolveOption(options, "requestMode", properties.getRequestMode());
        if ("sync".equalsIgnoreCase(requestMode)) {
            return parseOfficialSync(content, mimeType, options);
        }
        return parseOfficialAsync(content, mimeType, options);
    }

    private ParseResult parseOfficialSync(byte[] content, String mimeType, Map<String, Object> options) {
        String syncUrl = resolveOption(options, "syncUrl", properties.getSyncUrl());
        if (!StringUtils.hasText(syncUrl)) {
            throw new ServiceException("Paddle syncUrl is required");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("file", Base64.getEncoder().encodeToString(content));
        payload.put("fileType", resolveFileType(mimeType));
        payload.putAll(buildOfficialOptionalPayload(options));

        Request request = new Request.Builder()
                .url(syncUrl)
                .post(RequestBody.create(writeJson(payload), HttpMediaTypes.JSON))
                .addHeader("Authorization", "token " + requireApiKey())
                .addHeader("Content-Type", HttpMediaTypes.JSON_UTF8_HEADER)
                .build();

        JsonNode root = executeJson(request, "Paddle official sync analysis");
        List<JsonNode> pageResults = extractLayoutParsingResults(root.path("result"));
        return buildOfficialParseResult(pageResults, "sync", options, root.path("result"));
    }

    private ParseResult parseOfficialAsync(byte[] content, String mimeType, Map<String, Object> options) {
        String jobUrl = resolveOption(options, "asyncJobUrl", properties.getAsyncJobUrl());
        if (!StringUtils.hasText(jobUrl)) {
            throw new ServiceException("Paddle asyncJobUrl is required");
        }

        String fileName = resolveFileName(options, mimeType);
        MultipartBody.Builder form = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", resolveOption(options, "model", properties.getModel()))
                .addFormDataPart("optionalPayload", writeJson(buildOfficialOptionalPayload(options)))
                .addFormDataPart(
                        "file",
                        fileName,
                        RequestBody.create(content, resolveMediaType(mimeType))
                );

        Request submitRequest = new Request.Builder()
                .url(jobUrl)
                .post(form.build())
                .addHeader("Authorization", "bearer " + requireApiKey())
                .build();

        JsonNode submitRoot = executeJson(submitRequest, "Paddle official async submit");
        String jobId = submitRoot.path("data").path("jobId").asText("");
        if (!StringUtils.hasText(jobId)) {
            throw new ServiceException("Paddle async submit did not return jobId");
        }

        JsonNode jobData = pollJobResult(jobUrl, jobId);
        String jsonUrl = jobData.path("resultUrl").path("jsonUrl").asText("");
        if (!StringUtils.hasText(jsonUrl)) {
            throw new ServiceException("Paddle async resultUrl.jsonUrl is empty");
        }

        String jsonl = executeText(new Request.Builder().url(jsonUrl).get().build(), "Paddle async result download");
        List<JsonNode> pageResults = parseJsonlResults(jsonl);
        return buildOfficialParseResult(pageResults, "async", options, jobData);
    }

    private JsonNode pollJobResult(String jobUrl, String jobId) {
        long deadline = System.currentTimeMillis() + Math.max(properties.getAsyncTimeoutMs(), 10_000L);
        long intervalMs = Math.max(properties.getAsyncPollIntervalMs(), 1_000L);
        String statusUrl = jobUrl.endsWith("/") ? jobUrl + jobId : jobUrl + "/" + jobId;

        while (System.currentTimeMillis() < deadline) {
            Request request = new Request.Builder()
                    .url(statusUrl)
                    .get()
                    .addHeader("Authorization", "bearer " + requireApiKey())
                    .build();
            JsonNode root = executeJson(request, "Paddle async status poll");
            JsonNode data = root.path("data");
            String state = data.path("state").asText("");
            if ("done".equalsIgnoreCase(state)) {
                return data;
            }
            if ("failed".equalsIgnoreCase(state)) {
                String errorMsg = data.path("errorMsg").asText("unknown error");
                throw new ServiceException("Paddle async job failed: " + errorMsg);
            }
            sleep(intervalMs);
        }
        throw new ServiceException("Paddle async job timed out after " + Duration.ofMillis(properties.getAsyncTimeoutMs()));
    }

    private ParseResult buildOfficialParseResult(List<JsonNode> pageResults,
                                                 String requestMode,
                                                 Map<String, Object> options,
                                                 JsonNode rawMetadataNode) {
        List<StructuredDocument.VisualBlock> visualBlocks = new ArrayList<>();
        StringBuilder combinedText = new StringBuilder();
        Path runDir = prepareResultRunDir();

        for (int pageIndex = 0; pageIndex < pageResults.size(); pageIndex++) {
            JsonNode pageNode = pageResults.get(pageIndex);
            int pageNo = resolvePageNo(pageNode, pageIndex);
            String markdownText = TextCleanupUtil.cleanup(pageNode.path("markdown").path("text").asText(""));
            if (StringUtils.hasText(markdownText)) {
                if (combinedText.length() > 0) {
                    combinedText.append("\n\n");
                }
                combinedText.append(markdownText);
            }

            Map<String, String> markdownImages = collectRemoteImages(
                    pageNode.path("markdown").path("images"),
                    runDir.resolve("page-" + pageNo).resolve("markdown")
            );
            Map<String, String> outputImages = collectRemoteImages(
                    pageNode.path("outputImages"),
                    runDir.resolve("page-" + pageNo).resolve("output")
            );

            String imageUri = firstImageUri(outputImages);
            if (!StringUtils.hasText(imageUri)) {
                imageUri = firstImageUri(markdownImages);
            }

            Map<String, Object> blockMetadata = new LinkedHashMap<>();
            blockMetadata.put("provider", "official");
            blockMetadata.put("request_mode", requestMode);
            blockMetadata.put("model", resolveOption(options, "model", properties.getModel()));
            if (!markdownImages.isEmpty()) {
                blockMetadata.put("markdown_images", markdownImages);
            }
            if (!outputImages.isEmpty()) {
                blockMetadata.put("output_images", outputImages);
            }

            visualBlocks.add(StructuredDocument.VisualBlock.builder()
                    .blockId("paddle-page-" + pageNo)
                    .blockType("page")
                    .pageNo(pageNo)
                    .imageUri(imageUri)
                    .text(markdownText)
                    .markdown(markdownText)
                    .summary(summarize(markdownText))
                    .nearbyContext(markdownText)
                    .metadata(blockMetadata)
                    .build());
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "official");
        metadata.put("requestMode", requestMode);
        metadata.put("model", resolveOption(options, "model", properties.getModel()));
        metadata.put("pageCount", pageResults.size());
        if (rawMetadataNode != null && !rawMetadataNode.isMissingNode() && !rawMetadataNode.isNull()) {
            metadata.put("rawResult", objectMapper.convertValue(rawMetadataNode, MAP_TYPE));
        }

        StructuredDocument document = StructuredDocument.builder()
                .text(combinedText.toString())
                .visualBlocks(visualBlocks)
                .metadata(metadata)
                .build();
        return ParseResult.of(document.getText(), metadata, document);
    }

    private List<JsonNode> parseJsonlResults(String jsonl) {
        List<JsonNode> results = new ArrayList<>();
        if (!StringUtils.hasText(jsonl)) {
            return results;
        }
        String[] lines = jsonl.split("\\r?\\n");
        for (String line : lines) {
            if (!StringUtils.hasText(line)) {
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(line);
                results.addAll(extractLayoutParsingResults(root.path("result")));
            } catch (IOException e) {
                throw new ServiceException("Parse Paddle async jsonl failed: " + e.getMessage());
            }
        }
        return results;
    }

    private List<JsonNode> extractLayoutParsingResults(JsonNode resultNode) {
        List<JsonNode> results = new ArrayList<>();
        if (resultNode == null || resultNode.isMissingNode() || resultNode.isNull()) {
            return results;
        }
        JsonNode layoutResults = resultNode.path("layoutParsingResults");
        if (layoutResults.isArray()) {
            layoutResults.forEach(results::add);
        }
        return results;
    }

    private Map<String, Object> buildOfficialOptionalPayload(Map<String, Object> options) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("useDocOrientationClassify",
                resolveBooleanOption(options, "useDocOrientationClassify", properties.isUseDocOrientationClassify()));
        payload.put("useDocUnwarping",
                resolveBooleanOption(options, "useDocUnwarping", properties.isUseDocUnwarping()));
        payload.put("useChartRecognition",
                resolveBooleanOption(options, "useChartRecognition", properties.isUseChartRecognition()));
        return payload;
    }

    private Path prepareResultRunDir() {
        Path root = Paths.get(properties.getResultDownloadDir());
        Path runDir = root.resolve(UUID.randomUUID().toString().replace("-", ""));
        try {
            Files.createDirectories(runDir);
        } catch (IOException e) {
            throw new ServiceException("Create Paddle result directory failed: " + e.getMessage());
        }
        return runDir;
    }

    private Map<String, String> collectRemoteImages(JsonNode imagesNode, Path targetDir) {
        Map<String, String> images = new LinkedHashMap<>();
        if (imagesNode == null || !imagesNode.isObject()) {
            return images;
        }
        imagesNode.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            String remoteUrl = entry.getValue().asText("");
            if (!StringUtils.hasText(remoteUrl)) {
                return;
            }
            String resolvedUri = properties.isDownloadRemoteImages()
                    ? downloadRemoteImage(remoteUrl, targetDir, key)
                    : remoteUrl;
            images.put(key, resolvedUri);
        });
        return images;
    }

    private String downloadRemoteImage(String remoteUrl, Path targetDir, String preferredName) {
        try {
            Files.createDirectories(targetDir);
        } catch (IOException e) {
            throw new ServiceException("Create Paddle image directory failed: " + e.getMessage());
        }

        Request request = new Request.Builder().url(remoteUrl).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return remoteUrl;
            }
            ResponseBody body = response.body();
            if (body == null) {
                return remoteUrl;
            }
            String fileName = sanitizeFileName(preferredName);
            String extension = resolveExtension(remoteUrl, response.header("Content-Type"));
            if (!fileName.endsWith(extension)) {
                fileName = fileName + extension;
            }
            Path filePath = targetDir.resolve(fileName);
            Files.write(filePath, body.bytes());
            return filePath.toAbsolutePath().toString();
        } catch (Exception e) {
            log.warn("Download Paddle image failed, remoteUrl={}, reason={}", remoteUrl, e.getMessage());
            return remoteUrl;
        }
    }

    private StructuredDocument parseBridgeDocument(JsonNode root) {
        JsonNode documentNode = root != null && root.has("document") ? root.get("document") : root;
        if (documentNode == null || documentNode.isNull() || !documentNode.isObject()) {
            return StructuredDocument.builder().build();
        }

        StructuredDocument document = objectMapper.convertValue(documentNode, StructuredDocument.class);
        if (document == null) {
            document = StructuredDocument.builder().build();
        }

        if (!StringUtils.hasText(document.getText()) && root != null && root.has("text")) {
            document.setText(TextCleanupUtil.cleanup(root.path("text").asText("")));
        }
        if ((document.getMetadata() == null || document.getMetadata().isEmpty()) && root != null && root.has("metadata")) {
            document.setMetadata(objectMapper.convertValue(root.get("metadata"), MAP_TYPE));
        }
        return document;
    }

    private String resolveBridgeText(JsonNode root, StructuredDocument document) {
        if (document != null && StringUtils.hasText(document.getText())) {
            return TextCleanupUtil.cleanup(document.getText());
        }
        if (root != null && root.has("markdown") && StringUtils.hasText(root.path("markdown").asText())) {
            return TextCleanupUtil.cleanup(root.path("markdown").asText(""));
        }
        if (root != null && root.has("text")) {
            return TextCleanupUtil.cleanup(root.path("text").asText(""));
        }
        return "";
    }

    private Map<String, Object> parseBridgeMetadata(JsonNode root, StructuredDocument document) {
        if (document != null && document.getMetadata() != null && !document.getMetadata().isEmpty()) {
            return document.getMetadata();
        }
        if (root != null && root.has("metadata") && root.get("metadata").isObject()) {
            return objectMapper.convertValue(root.get("metadata"), MAP_TYPE);
        }
        return Map.of();
    }

    private String buildBridgeUrl() {
        String baseUrl = properties.getBaseUrl();
        String endpoint = properties.getEndpoint();
        if (!StringUtils.hasText(baseUrl)) {
            throw new ServiceException("Paddle document analysis baseUrl is required");
        }
        if (!StringUtils.hasText(endpoint)) {
            return baseUrl;
        }
        boolean baseEndsWithSlash = baseUrl.endsWith("/");
        boolean endpointStartsWithSlash = endpoint.startsWith("/");
        if (baseEndsWithSlash && endpointStartsWithSlash) {
            return baseUrl + endpoint.substring(1);
        }
        if (!baseEndsWithSlash && !endpointStartsWithSlash) {
            return baseUrl + "/" + endpoint;
        }
        return baseUrl + endpoint;
    }

    private JsonNode executeJson(Request request, String action) {
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = readBody(response.body());
                log.warn("{} failed: status={}, body={}", action, response.code(), body);
                throw new ServiceException(action + " failed: HTTP " + response.code());
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new ServiceException(action + " response body is empty");
            }
            return objectMapper.readTree(responseBody.string());
        } catch (IOException e) {
            throw new ServiceException(action + " failed: " + e.getMessage());
        }
    }

    private String executeText(Request request, String action) {
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = readBody(response.body());
                log.warn("{} failed: status={}, body={}", action, response.code(), body);
                throw new ServiceException(action + " failed: HTTP " + response.code());
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new ServiceException(action + " response body is empty");
            }
            return responseBody.string();
        } catch (IOException e) {
            throw new ServiceException(action + " failed: " + e.getMessage());
        }
    }

    private String readBody(ResponseBody body) throws IOException {
        if (body == null) {
            return "";
        }
        return new String(body.bytes(), StandardCharsets.UTF_8);
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            throw new ServiceException("Build Paddle request failed: " + e.getMessage());
        }
    }

    private String requireApiKey() {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new ServiceException("Paddle apiKey is required");
        }
        return properties.getApiKey();
    }

    private int resolveFileType(String mimeType) {
        return mimeType != null && mimeType.toLowerCase().contains("pdf") ? 0 : 1;
    }

    private MediaType resolveMediaType(String mimeType) {
        if (!StringUtils.hasText(mimeType)) {
            return DEFAULT_FILE_MEDIA_TYPE;
        }
        MediaType mediaType = MediaType.parse(mimeType);
        return mediaType == null ? DEFAULT_FILE_MEDIA_TYPE : mediaType;
    }

    private String resolveFileName(Map<String, Object> options, String mimeType) {
        String fileName = resolveOption(options, "fileName", null);
        if (StringUtils.hasText(fileName)) {
            return Paths.get(fileName).getFileName().toString();
        }
        return resolveFileType(mimeType) == 0 ? "document.pdf" : "image.png";
    }

    private String resolveOption(Map<String, Object> options, String key, String defaultValue) {
        if (options == null) {
            return defaultValue;
        }
        Object value = options.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private boolean resolveBooleanOption(Map<String, Object> options, String key, boolean defaultValue) {
        if (options == null || !options.containsKey(key)) {
            return defaultValue;
        }
        Object value = options.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private int resolvePageNo(JsonNode pageNode, int pageIndex) {
        if (pageNode != null) {
            JsonNode pageNoNode = pageNode.get("pageNo");
            if (pageNoNode != null && pageNoNode.canConvertToInt()) {
                return pageNoNode.asInt();
            }
            JsonNode pageIndexNode = pageNode.get("pageIndex");
            if (pageIndexNode != null && pageIndexNode.canConvertToInt()) {
                return pageIndexNode.asInt() + 1;
            }
        }
        return pageIndex + 1;
    }

    private String firstImageUri(Map<String, String> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        return images.values().iterator().next();
    }

    private String summarize(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 240) {
            return normalized;
        }
        return normalized.substring(0, 240) + "...";
    }

    private String sanitizeFileName(String input) {
        if (!StringUtils.hasText(input)) {
            return UUID.randomUUID().toString().replace("-", "");
        }
        return input.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }

    private String resolveExtension(String remoteUrl, String contentType) {
        String byType = extensionByContentType(contentType);
        if (StringUtils.hasText(byType)) {
            return byType;
        }
        try {
            String path = URI.create(remoteUrl).getPath();
            if (StringUtils.hasText(path)) {
                int dot = path.lastIndexOf('.');
                if (dot >= 0 && dot < path.length() - 1) {
                    return path.substring(dot);
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return ".bin";
    }

    private String extensionByContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return null;
        }
        String normalized = contentType.toLowerCase();
        if (normalized.contains("png")) {
            return ".png";
        }
        if (normalized.contains("jpeg") || normalized.contains("jpg")) {
            return ".jpg";
        }
        if (normalized.contains("webp")) {
            return ".webp";
        }
        if (normalized.contains("gif")) {
            return ".gif";
        }
        return null;
    }

    private void sleep(long intervalMs) {
        try {
            Thread.sleep(intervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("Paddle async polling interrupted");
        }
    }
}
