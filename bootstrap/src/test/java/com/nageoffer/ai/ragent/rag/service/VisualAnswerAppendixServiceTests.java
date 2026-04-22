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

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.service.support.MediaPreviewService;
import com.nageoffer.ai.ragent.rag.service.support.VisualAnswerAppendixService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VisualAnswerAppendixServiceTests {

    @Test
    void shouldBuildMarkdownWithPreviewImages() {
        MediaPreviewService mediaPreviewService = mock(MediaPreviewService.class);
        when(mediaPreviewService.buildPreviewUrl(anyString()))
                .thenReturn("/api/ragent/rag/media/preview?uri=s3%3A%2F%2Frag-media%2Fchart.png");
        RAGDefaultProperties defaults = new RAGDefaultProperties();
        defaults.setVisualAnswerImageLimit(2);
        VisualAnswerAppendixService service = new VisualAnswerAppendixService(mediaPreviewService, defaults);

        RetrievedChunk chunk = RetrievedChunk.builder()
                .id("img-1")
                .text("Fallback text")
                .score(0.92f)
                .metadata(Map.of(
                        "content_type", "visual",
                        "image_uri", "s3://rag-media/chart.png",
                        "summary", "Product category overview",
                        "page_no", 3
                ))
                .build();

        String markdown = service.buildMarkdown(Map.of("visual", List.of(chunk)));

        Assertions.assertTrue(markdown.contains("## 相关图片"));
        Assertions.assertTrue(markdown.contains("![Product category overview](</api/ragent/rag/media/preview?uri=s3%3A%2F%2Frag-media%2Fchart.png>)"));
        Assertions.assertTrue(markdown.contains("页码：3"));
        Assertions.assertTrue(markdown.contains("匹配分：0.920"));
    }
}
