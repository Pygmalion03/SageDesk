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
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
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
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaddleDocumentParser implements DocumentParser {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final MediaType DEFAULT_FILE_MEDIA_TYPE = MediaType.parse("application/octet-stream");
    private static final Pattern IMAGE_BOX_PATTERN = Pattern.compile(".*img_in_image_box_(\\d+)_(\\d+)_(\\d+)_(\\d+)\\.[^.]+$");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final DocumentAnalysisProperties properties;
    private final FileStorageService fileStorageService;
    private final S3Client s3Client;

    private final Set<String> ensuredBuckets = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<Integer, Semaphore> officialAsyncSemaphores = new ConcurrentHashMap<>();

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
        Semaphore semaphore = officialAsyncSemaphore();
        acquireOfficialAsyncPermit(semaphore);
        try {
            return doParseOfficialAsync(content, mimeType, options);
        } finally {
            semaphore.release();
        }
    }

    private ParseResult doParseOfficialAsync(byte[] content, String mimeType, Map<String, Object> options) {
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

        JsonNode submitRoot = executeJsonWithRetry(submitRequest, "Paddle official async submit", options);
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
                    runDir.resolve("page-" + pageNo).resolve("markdown"),
                    options
            );
            Map<String, String> outputImages = collectRemoteImages(
                    pageNode.path("outputImages"),
                    runDir.resolve("page-" + pageNo).resolve("output"),
                    options
            );

            String imageUri = firstContentImageUri(markdownImages, outputImages);

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

    private Map<String, String> collectRemoteImages(JsonNode imagesNode, Path targetDir, Map<String, Object> options) {
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
                    ? downloadRemoteImage(remoteUrl, targetDir, key, options)
                    : remoteUrl;
            images.put(key, resolvedUri);
        });
        return images;
    }

    private String downloadRemoteImage(String remoteUrl, Path targetDir, String preferredName, Map<String, Object> options) {
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
            String contentType = response.header("Content-Type");
            String extension = resolveExtension(remoteUrl, contentType);
            if (!fileName.endsWith(extension)) {
                fileName = fileName + extension;
            }
            Path filePath = targetDir.resolve(fileName);
            byte[] imageBytes = body.bytes();
            Files.write(filePath, imageBytes);
            String storedUri = uploadImageToStorage(imageBytes, fileName, contentType, options);
            return StringUtils.hasText(storedUri) ? storedUri : filePath.toAbsolutePath().toString();
        } catch (Exception e) {
            log.warn("Download Paddle image failed, remoteUrl={}, reason={}", remoteUrl, e.getMessage());
            return remoteUrl;
        }
    }

    private String uploadImageToStorage(byte[] imageBytes,
                                        String fileName,
                                        String contentType,
                                        Map<String, Object> options) {
        String bucketName = normalizeBucketName(resolveOption(options, "storageBucket", null));
        if (!StringUtils.hasText(bucketName) || imageBytes == null || imageBytes.length == 0) {
            return null;
        }
        ensureBucketExists(bucketName);
        StoredFileDTO storedFile = fileStorageService.upload(bucketName, imageBytes, fileName, contentType);
        return storedFile == null ? null : storedFile.getUrl();
    }

    private void ensureBucketExists(String bucketName) {
        if (!StringUtils.hasText(bucketName) || ensuredBuckets.contains(bucketName)) {
            return;
        }
        synchronized (ensuredBuckets) {
            if (ensuredBuckets.contains(bucketName)) {
                return;
            }
            try {
                s3Client.createBucket(builder -> builder.bucket(bucketName));
                log.info("Created Paddle media bucket: {}", bucketName);
            } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException ignored) {
                log.debug("Paddle media bucket already exists: {}", bucketName);
            }
            ensuredBuckets.add(bucketName);
        }
    }

    private String normalizeBucketName(String rawBucketName) {
        if (!StringUtils.hasText(rawBucketName)) {
            return null;
        }
        String normalized = rawBucketName.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9.-]", "-")
                .replaceAll("^[^a-z0-9]+", "")
                .replaceAll("[^a-z0-9]+$", "")
                .replaceAll("-{2,}", "-");
        if (!StringUtils.hasText(normalized)) {
            normalized = "rag-visual-media";
        }
        if (normalized.length() < 3) {
            normalized = (normalized + "-media");
        }
        if (normalized.length() > 63) {
            normalized = normalized.substring(0, 63).replaceAll("[^a-z0-9]+$", "");
        }
        if (normalized.length() < 3) {
            return "rag-visual-media";
        }
        return normalized;
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

    private JsonNode executeJsonWithRetry(Request request, String action, Map<String, Object> options) {
        int maxAttempts = Math.max(
                resolveIntOption(options, "retryMaxAttempts", properties.getAsyncSubmitMaxAttempts()),
                1
        );
        long delayMs = Math.max(
                resolveLongOption(options, "retryInitialDelayMs", properties.getAsyncSubmitRetryInitialDelayMs()),
                0L
        );
        long maxDelayMs = Math.max(
                resolveLongOption(options, "retryMaxDelayMs", properties.getAsyncSubmitRetryMaxDelayMs()),
                delayMs
        );

        ServiceException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return executeJson(request, action);
            } catch (ServiceException ex) {
                last = ex;
                if (attempt >= maxAttempts || !isRetryableOfficialSubmitError(ex)) {
                    throw ex;
                }
                log.warn("{} retryable failure, attempt={}/{}, retryAfterMs={}, reason={}",
                        action, attempt, maxAttempts, delayMs, ex.getErrorMessage());
                sleep(delayMs, action + " retry interrupted");
                delayMs = nextRetryDelay(delayMs, maxDelayMs);
            }
        }
        throw last == null ? new ServiceException(action + " failed") : last;
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

    private int resolveIntOption(Map<String, Object> options, String key, int defaultValue) {
        if (options == null || !options.containsKey(key)) {
            return defaultValue;
        }
        Object value = options.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private long resolveLongOption(Map<String, Object> options, String key, long defaultValue) {
        if (options == null || !options.containsKey(key)) {
            return defaultValue;
        }
        Object value = options.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
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

    private String firstContentImageUri(Map<String, String> markdownImages, Map<String, String> outputImages) {
        String outputImageUri = firstNonDebugOutputImageUri(outputImages);
        if (StringUtils.hasText(outputImageUri)) {
            return outputImageUri;
        }
        String imageUri = largestImageUri(markdownImages);
        if (StringUtils.hasText(imageUri)) {
            return imageUri;
        }
        return null;
    }

    private String firstNonDebugOutputImageUri(Map<String, String> outputImages) {
        if (outputImages == null || outputImages.isEmpty()) {
            return null;
        }
        return outputImages.entrySet().stream()
                .filter(entry -> !isLayoutDebugImage(entry.getKey()))
                .map(Map.Entry::getValue)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    private String largestImageUri(Map<String, String> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        Map.Entry<String, String> best = null;
        long bestArea = -1L;
        for (Map.Entry<String, String> entry : images.entrySet()) {
            if (entry == null || !StringUtils.hasText(entry.getValue())) {
                continue;
            }
            long area = imageBoxArea(entry.getKey());
            if (best == null || area > bestArea) {
                best = entry;
                bestArea = area;
            }
        }
        return best == null ? null : best.getValue();
    }

    private long imageBoxArea(String imageName) {
        if (!StringUtils.hasText(imageName)) {
            return 0L;
        }
        Matcher matcher = IMAGE_BOX_PATTERN.matcher(imageName);
        if (!matcher.matches()) {
            return 0L;
        }
        long x1 = parseLong(matcher.group(1));
        long y1 = parseLong(matcher.group(2));
        long x2 = parseLong(matcher.group(3));
        long y2 = parseLong(matcher.group(4));
        long width = Math.max(x2 - x1, 0L);
        long height = Math.max(y2 - y1, 0L);
        return width * height;
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private boolean isLayoutDebugImage(String imageName) {
        if (!StringUtils.hasText(imageName)) {
            return false;
        }
        String normalized = imageName.toLowerCase(Locale.ROOT);
        return normalized.contains("layout_det_res")
                || normalized.contains("layout_res")
                || normalized.contains("det_res")
                || normalized.contains("ocr_res");
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
        sleep(intervalMs, "Paddle async polling interrupted");
    }

    private void sleep(long intervalMs, String interruptedMessage) {
        if (intervalMs <= 0) {
            return;
        }
        try {
            Thread.sleep(intervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException(interruptedMessage);
        }
    }

    private Semaphore officialAsyncSemaphore() {
        int maxConcurrent = Math.max(properties.getAsyncMaxConcurrentJobs(), 1);
        return officialAsyncSemaphores.computeIfAbsent(maxConcurrent, Semaphore::new);
    }

    private void acquireOfficialAsyncPermit(Semaphore semaphore) {
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("Paddle async job throttling interrupted");
        }
    }

    private boolean isRetryableOfficialSubmitError(ServiceException ex) {
        String message = ex.getErrorMessage();
        if (!StringUtils.hasText(message)) {
            return false;
        }
        return message.contains("HTTP 408")
                || message.contains("HTTP 429")
                || message.contains("HTTP 500")
                || message.contains("HTTP 502")
                || message.contains("HTTP 503")
                || message.contains("HTTP 504");
    }

    private long nextRetryDelay(long currentDelayMs, long maxDelayMs) {
        if (currentDelayMs <= 0) {
            return 0;
        }
        long next = currentDelayMs * 2;
        if (next < 0) {
            return maxDelayMs;
        }
        return Math.min(next, maxDelayMs);
    }
}
