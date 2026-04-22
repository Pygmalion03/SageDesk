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
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.config.DocumentAnalysisProperties;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class MediaPreviewService {

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    private final FileStorageService fileStorageService;
    private final DocumentAnalysisProperties documentAnalysisProperties;

    public String buildPreviewUrl(String imageUri) {
        if (StrUtil.isBlank(imageUri)) {
            return null;
        }
        String trimmed = imageUri.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("data:")) {
            return trimmed;
        }
        return StrUtil.blankToDefault(contextPath, "") + "/rag/media/preview?uri="
                + URLEncoder.encode(trimmed, StandardCharsets.UTF_8);
    }

    public MediaPayload load(String imageUri) {
        if (StrUtil.isBlank(imageUri)) {
            throw new ClientException("image uri is required");
        }
        String trimmed = imageUri.trim();
        if (trimmed.startsWith("s3://")) {
            return loadFromS3(trimmed);
        }
        Path localPath = resolveAllowedLocalPath(trimmed);
        if (localPath != null) {
            return loadLocal(localPath);
        }
        throw new ClientException("unsupported image uri: " + trimmed);
    }

    private MediaPayload loadFromS3(String imageUri) {
        try (InputStream inputStream = fileStorageService.openStream(imageUri)) {
            byte[] bytes = inputStream.readAllBytes();
            return new MediaPayload(bytes, inferMimeType(imageUri), extractFileName(imageUri));
        } catch (Exception ex) {
            throw new ClientException("open s3 image failed: " + ex.getMessage());
        }
    }

    private MediaPayload loadLocal(Path filePath) {
        try {
            byte[] bytes = Files.readAllBytes(filePath);
            String contentType = Files.probeContentType(filePath);
            if (StrUtil.isBlank(contentType)) {
                contentType = inferMimeType(filePath.getFileName().toString());
            }
            return new MediaPayload(bytes, contentType, filePath.getFileName().toString());
        } catch (Exception ex) {
            throw new ClientException("read local image failed: " + ex.getMessage());
        }
    }

    private Path resolveAllowedLocalPath(String imageUri) {
        try {
            Path candidate = imageUri.startsWith("file:/")
                    ? Paths.get(java.net.URI.create(imageUri))
                    : Paths.get(imageUri);
            candidate = candidate.toAbsolutePath().normalize();
            if (!Files.exists(candidate) || Files.isDirectory(candidate)) {
                return null;
            }
            boolean allowed = buildAllowedRoots().stream().anyMatch(candidate::startsWith);
            if (!allowed) {
                throw new ClientException("local image path is not allowed");
            }
            return candidate;
        } catch (ClientException ex) {
            throw ex;
        } catch (Exception ex) {
            return null;
        }
    }

    private Set<Path> buildAllowedRoots() {
        Set<Path> roots = new LinkedHashSet<>();
        Path configuredRoot = Paths.get(documentAnalysisProperties.getResultDownloadDir()).toAbsolutePath().normalize();
        roots.add(configuredRoot);
        Path parent = configuredRoot.getParent();
        if (parent != null) {
            roots.add(parent.resolve("paddle_bridge_runtime").toAbsolutePath().normalize());
        }
        return roots;
    }

    private String extractFileName(String imageUri) {
        int slash = imageUri.lastIndexOf('/');
        if (slash < 0 || slash >= imageUri.length() - 1) {
            return "image";
        }
        return imageUri.substring(slash + 1);
    }

    private String inferMimeType(String imageUri) {
        String lower = StrUtil.emptyIfNull(imageUri).toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".bmp")) {
            return "image/bmp";
        }
        return "application/octet-stream";
    }

    public record MediaPayload(byte[] bytes, String contentType, String fileName) {
    }
}
