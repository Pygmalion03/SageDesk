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

package com.nageoffer.ai.ragent.rag.service;

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.config.DocumentAnalysisProperties;
import com.nageoffer.ai.ragent.rag.service.support.MediaPreviewService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.Mockito.mock;

class MediaPreviewServiceTests {

    @TempDir
    Path tempDir;

    @Test
    void shouldBuildProxyPreviewUrlForStoredImage() {
        DocumentAnalysisProperties properties = new DocumentAnalysisProperties();
        properties.setResultDownloadDir(tempDir.toString());
        MediaPreviewService service = new MediaPreviewService(mock(FileStorageService.class), properties);
        ReflectionTestUtils.setField(service, "contextPath", "/api/ragent");

        String previewUrl = service.buildPreviewUrl("s3://rag-media/chart.png");

        Assertions.assertEquals(
                "/api/ragent/rag/media/preview?uri=s3%3A%2F%2Frag-media%2Fchart.png",
                previewUrl
        );
    }

    @Test
    void shouldLoadLocalImageWithinAllowedDirectory() throws Exception {
        Path imagePath = tempDir.resolve("page-1").resolve("chart.png");
        Files.createDirectories(imagePath.getParent());
        Files.write(imagePath, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});

        DocumentAnalysisProperties properties = new DocumentAnalysisProperties();
        properties.setResultDownloadDir(tempDir.toString());
        MediaPreviewService service = new MediaPreviewService(mock(FileStorageService.class), properties);

        MediaPreviewService.MediaPayload payload = service.load(imagePath.toString());

        Assertions.assertEquals("chart.png", payload.fileName());
        Assertions.assertEquals("image/png", payload.contentType());
        Assertions.assertArrayEquals(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}, payload.bytes());
    }

    @Test
    void shouldRejectLocalImageOutsideAllowedDirectory() throws Exception {
        Path otherRoot = Files.createTempDirectory("media-preview-outside");
        Path imagePath = otherRoot.resolve("chart.png");
        Files.write(imagePath, new byte[]{1, 2, 3});

        DocumentAnalysisProperties properties = new DocumentAnalysisProperties();
        properties.setResultDownloadDir(tempDir.toString());
        MediaPreviewService service = new MediaPreviewService(mock(FileStorageService.class), properties);

        Assertions.assertThrows(ClientException.class, () -> service.load(imagePath.toString()));
    }

    @Test
    void shouldAllowLegacyBridgeRuntimeImage() throws Exception {
        Path legacyRoot = Path.of("E:\\Projects\\ragent\\scripts\\paddle_bridge_runtime");
        Files.createDirectories(legacyRoot);
        Path imagePath = legacyRoot.resolve("legacy-preview.png");
        try {
            Files.write(imagePath, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});

            DocumentAnalysisProperties properties = new DocumentAnalysisProperties();
            properties.setResultDownloadDir("E:\\Projects\\ragent\\scripts\\paddle_api_runtime");
            MediaPreviewService service = new MediaPreviewService(mock(FileStorageService.class), properties);

            MediaPreviewService.MediaPayload payload = service.load(imagePath.toString());

            Assertions.assertEquals("legacy-preview.png", payload.fileName());
            Assertions.assertEquals("image/png", payload.contentType());
        } finally {
            Files.deleteIfExists(imagePath);
        }
    }
}
