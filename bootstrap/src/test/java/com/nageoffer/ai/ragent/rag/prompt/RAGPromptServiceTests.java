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

package com.nageoffer.ai.ragent.rag.prompt;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptContext;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RAGPromptServiceTests {

    @TempDir
    Path tempDir;

    @Test
    void shouldBuildMultimodalUserMessageFromVisualChunks() throws Exception {
        Path imagePath = tempDir.resolve("chart.png");
        Files.write(imagePath, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});

        PromptTemplateLoader loader = new PromptTemplateLoader(new DefaultResourceLoader());
        RAGDefaultProperties defaults = new RAGDefaultProperties();
        defaults.setVisualAnswerImageLimit(2);
        RAGPromptService promptService = new RAGPromptService(loader, defaults, mock(FileStorageService.class));

        RetrievedChunk visualChunk = RetrievedChunk.builder()
                .id("img-1")
                .text("Revenue chart summary")
                .score(0.98f)
                .metadata(Map.of(
                        "content_type", "visual",
                        "image_uri", imagePath.toString(),
                        "summary", "Revenue chart"
                ))
                .build();

        PromptContext context = PromptContext.builder()
                .question("What does this image explain?")
                .kbContext("Matched text evidence")
                .intentChunks(Map.of("visual", List.of(visualChunk)))
                .build();

        List<ChatMessage> messages = promptService.buildStructuredMessages(
                context,
                List.of(),
                "What does this image explain?",
                List.of("What does this image explain?")
        );

        ChatMessage userMessage = messages.get(messages.size() - 1);
        Assertions.assertTrue(userMessage.hasParts());
        Assertions.assertTrue(userMessage.hasImageParts());
        Assertions.assertEquals(ChatMessage.Role.USER, userMessage.getRole());
        Assertions.assertTrue(userMessage.getTextContent().contains("Matched text evidence"));
        Assertions.assertTrue(userMessage.getTextContent().contains("图片使用说明"));
        Assertions.assertEquals(2, userMessage.getParts().size());
        Assertions.assertTrue(userMessage.getParts().get(1).getImageUrl().startsWith("data:image/png;base64,"));
    }

    @Test
    void shouldFallbackToPlainUserMessageWhenNoVisualPayloadAvailable() {
        PromptTemplateLoader loader = new PromptTemplateLoader(new DefaultResourceLoader());
        RAGDefaultProperties defaults = new RAGDefaultProperties();
        RAGPromptService promptService = new RAGPromptService(loader, defaults, mock(FileStorageService.class));

        PromptContext context = PromptContext.builder()
                .question("Normal text question")
                .kbContext("Only text evidence")
                .intentChunks(Map.of())
                .build();

        List<ChatMessage> messages = promptService.buildStructuredMessages(
                context,
                List.of(),
                "Normal text question",
                List.of("Normal text question")
        );

        ChatMessage userMessage = messages.get(messages.size() - 1);
        Assertions.assertFalse(userMessage.hasParts());
        Assertions.assertFalse(userMessage.hasImageParts());
        Assertions.assertTrue(userMessage.getContent().contains("Normal text question"));
    }

    @Test
    void shouldResolveS3ImagePayloadViaStorageService() {
        PromptTemplateLoader loader = new PromptTemplateLoader(new DefaultResourceLoader());
        RAGDefaultProperties defaults = new RAGDefaultProperties();
        FileStorageService storageService = mock(FileStorageService.class);
        when(storageService.openStream("s3://rag-media/chart.png"))
                .thenReturn(new ByteArrayInputStream(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}));
        RAGPromptService promptService = new RAGPromptService(loader, defaults, storageService);

        RetrievedChunk visualChunk = RetrievedChunk.builder()
                .id("img-s3")
                .text("Cloud chart")
                .score(0.95f)
                .metadata(Map.of(
                        "content_type", "visual",
                        "image_uri", "s3://rag-media/chart.png",
                        "summary", "Cloud chart"
                ))
                .build();

        PromptContext context = PromptContext.builder()
                .question("What does this cloud chart show?")
                .kbContext("Matched text evidence")
                .intentChunks(Map.of("visual", List.of(visualChunk)))
                .build();

        List<ChatMessage> messages = promptService.buildStructuredMessages(
                context,
                List.of(),
                "What does this cloud chart show?",
                List.of("What does this cloud chart show?")
        );

        ChatMessage userMessage = messages.get(messages.size() - 1);
        Assertions.assertTrue(userMessage.hasImageParts());
        Assertions.assertTrue(userMessage.getParts().get(1).getImageUrl().startsWith("data:image/png;base64,"));
    }
}
