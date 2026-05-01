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

        Assertions.assertTrue(markdown.contains("**相关图片参考**（共1张）"));
        Assertions.assertTrue(markdown.contains("![Product category overview](</api/ragent/rag/media/preview?uri=s3%3A%2F%2Frag-media%2Fchart.png>)"));
        Assertions.assertTrue(markdown.contains("> Product category overview  页码: 3 | 相似度: 0.920"));
    }

    @Test
    void shouldCleanHtmlImageMarkupFromSummary() {
        MediaPreviewService mediaPreviewService = mock(MediaPreviewService.class);
        when(mediaPreviewService.buildPreviewUrl(anyString()))
                .thenReturn("/api/ragent/rag/media/preview?uri=s3%3A%2F%2Frag-media%2Fproduct.png");
        RAGDefaultProperties defaults = new RAGDefaultProperties();
        defaults.setVisualAnswerImageLimit(1);
        VisualAnswerAppendixService service = new VisualAnswerAppendixService(mediaPreviewService, defaults);

        RetrievedChunk chunk = RetrievedChunk.builder()
                .id("img-1")
                .text("""
                        <div style="text-align: center;"><img src="imgs/img_in_image_box_151_164_214_246.jpg" alt="Image" width="2%" /></div>

                        ## 越都 YD-338CC 系列
                        # 产品概览 Product Overview
                        <div style="text-align: center;">图 1：整机正面图</div>
                        """)
                .score(0.93f)
                .metadata(Map.of(
                        "content_type", "visual",
                        "image_uri", "s3://rag-media/product.png",
                        "page_no", 1
                ))
                .build();

        String markdown = service.buildMarkdown(Map.of("visual", List.of(chunk)));

        Assertions.assertFalse(markdown.contains("<div"));
        Assertions.assertFalse(markdown.contains("<img"));
        Assertions.assertFalse(markdown.contains("img_in_image_box"));
        Assertions.assertTrue(markdown.contains("越都 YD-338CC 系列"));
    }

    @Test
    void shouldDeduplicateSameSourceImageAcrossStoredUris() {
        MediaPreviewService mediaPreviewService = mock(MediaPreviewService.class);
        when(mediaPreviewService.buildPreviewUrl(anyString()))
                .thenAnswer(invocation -> "/preview?uri=" + invocation.getArgument(0));
        RAGDefaultProperties defaults = new RAGDefaultProperties();
        defaults.setVisualAnswerImageLimit(4);
        VisualAnswerAppendixService service = new VisualAnswerAppendixService(mediaPreviewService, defaults);

        String sourceImage = "imgs/img_in_image_box_340_483_1176_1815.jpg";
        RetrievedChunk first = visualChunk("img-1", "s3://rag-media/product-a.jpg", sourceImage, 0.916f);
        RetrievedChunk duplicate = visualChunk("img-2", "s3://rag-media/product-b.jpg", sourceImage, 0.901f);

        String markdown = service.buildMarkdown(Map.of("visual", List.of(first, duplicate)));

        Assertions.assertEquals(1, countOccurrences(markdown, "### 图片 "));
        Assertions.assertTrue(markdown.contains("product-a.jpg"));
        Assertions.assertFalse(markdown.contains("product-b.jpg"));
    }

    @Test
    void shouldPreferSelectedImageCaptionOverWholePageSummary() {
        MediaPreviewService mediaPreviewService = mock(MediaPreviewService.class);
        when(mediaPreviewService.buildPreviewUrl(anyString()))
                .thenReturn("/preview?uri=s3://rag-media/product.jpg");
        RAGDefaultProperties defaults = new RAGDefaultProperties();
        defaults.setVisualAnswerImageLimit(1);
        VisualAnswerAppendixService service = new VisualAnswerAppendixService(mediaPreviewService, defaults);

        String sourceImage = "imgs/img_in_image_box_340_483_1176_1815.jpg";
        RetrievedChunk chunk = RetrievedChunk.builder()
                .id("img-1")
                .text("""
                        <div style="text-align: center;"><img src="imgs/img_in_image_box_151_164_214_246.jpg" alt="Image" width="2%" /></div>
                        ## YD-338CC Series
                        # Product Overview
                        <div style="text-align: center;"><img src="imgs/img_in_image_box_340_483_1176_1815.jpg" alt="Image" /></div>
                        <div style="text-align: center;">Fig. 1: Full View</div>
                        <div style="text-align: center;"><img src="imgs/img_in_image_box_1375_312_2026_777.jpg" alt="Image" /></div>
                        <div style="text-align: center;">Fig. 2: Feeding View</div>
                        ✂ Shreddable Materials 📄 A4 Paper 📚 Magazines 💳 Cards
                        """)
                .score(0.916f)
                .metadata(Map.of(
                        "content_type", "visual",
                        "image_uri", "s3://rag-media/product.jpg",
                        "summary", "📄YD-338CC Series 🖼 Product Overview Fig. 1: Full View Fig. 2: Feeding View Fig. 3: Self-Lubricating Blades Fig. 4: Integrated Steel Cutter – 10-Year Warranty ✂ Shreddable Materials 📄 A4 Paper 📚 Magazines 💳 Cards 💿 CDs/DVDs 🧾 Receipts",
                        "markdown_images", Map.of(sourceImage, "s3://rag-media/product.jpg"),
                        "page_no", 18
                ))
                .build();

        String markdown = service.buildMarkdown(Map.of("visual", List.of(chunk)));

        Assertions.assertTrue(markdown.contains("Fig. 1: Full View"));
        Assertions.assertTrue(markdown.contains("Fig. 2: Feeding View"));
        Assertions.assertTrue(markdown.contains("Shreddable Materials"));
        Assertions.assertFalse(markdown.contains("📄"));
    }

    @Test
    void shouldPreferRichPageEvidenceOverSimpleImageCrop() {
        MediaPreviewService mediaPreviewService = mock(MediaPreviewService.class);
        when(mediaPreviewService.buildPreviewUrl(anyString()))
                .thenAnswer(invocation -> "/preview?uri=" + invocation.getArgument(0));
        RAGDefaultProperties defaults = new RAGDefaultProperties();
        defaults.setVisualAnswerImageLimit(4);
        VisualAnswerAppendixService service = new VisualAnswerAppendixService(mediaPreviewService, defaults);

        RetrievedChunk crop = RetrievedChunk.builder()
                .id("crop")
                .text("<div><img src=\"imgs/img_in_image_box_340_483_1176_1815.jpg\" /></div><div>Fig. 1: Full View</div>")
                .score(0.95f)
                .metadata(Map.of(
                        "content_type", "visual",
                        "image_uri", "s3://rag-media/crop.jpg",
                        "markdown_images", Map.of("imgs/img_in_image_box_340_483_1176_1815.jpg", "s3://rag-media/crop.jpg"),
                        "page_no", 18
                ))
                .build();
        RetrievedChunk page = RetrievedChunk.builder()
                .id("page")
                .text("""
                        YD-338CC Series Product Overview
                        Fig. 1: Full View Fig. 2: Feeding View Fig. 3: Self-Lubricating Blades
                        Shreddable Materials A4 Paper Magazines Cards CDs/DVDs Receipts
                        Technical Specifications Model Feed Width Cut Size Max Sheets Weight Power Paper Capacity Card Capacity Disc Capacity Price USD
                        """)
                .score(0.91f)
                .metadata(Map.of(
                        "content_type", "visual",
                        "block_id", "paddle-page-18",
                        "image_uri", "s3://rag-media/page.jpg",
                        "page_no", 18
                ))
                .build();

        String markdown = service.buildMarkdown(Map.of("visual", List.of(crop, page)));

        Assertions.assertEquals(1, countOccurrences(markdown, "](<"));
        Assertions.assertFalse(markdown.contains("crop.jpg"));
        Assertions.assertTrue(markdown.contains("page.jpg"));
        Assertions.assertTrue(markdown.contains("Technical Specifications"));
        Assertions.assertTrue(markdown.contains("Disc Capacity"));
        Assertions.assertTrue(markdown.contains("Price USD"));
    }

    @Test
    void shouldRenderSummaryAndFactsInSingleBlockquote() {
        MediaPreviewService mediaPreviewService = mock(MediaPreviewService.class);
        when(mediaPreviewService.buildPreviewUrl(anyString()))
                .thenReturn("/preview?uri=s3://rag-media/page.jpg");
        RAGDefaultProperties defaults = new RAGDefaultProperties();
        defaults.setVisualAnswerImageLimit(2);
        VisualAnswerAppendixService service = new VisualAnswerAppendixService(mediaPreviewService, defaults);

        RetrievedChunk chunk = RetrievedChunk.builder()
                .id("page")
                .text("fallback text")
                .score(0.916f)
                .metadata(Map.of(
                        "content_type", "visual",
                        "image_uri", "s3://rag-media/page.jpg",
                        "summary", "YD-338CC Series Product Overview Fig. 1 Full View Fig. 2 Feeding View Technical Specifications Paper Capacity Disc Capacity Price USD",
                        "page_no", 18
                ))
                .build();

        String markdown = service.buildMarkdown(Map.of("visual", List.of(chunk)));

        Assertions.assertTrue(markdown.contains("**相关图片参考**（共1张）"));
        Assertions.assertTrue(markdown.contains("![YD-338CC Series Product Overview Fig. 1 Full Vie](</preview?uri=s3://rag-media/page.jpg>)"));
        Assertions.assertTrue(markdown.contains("> YD-338CC Series Product Overview Fig. 1 Full View Fig. 2 Feeding View Technical Specifications Paper Capacity Disc Capacity Price USD  页码: 18 | 相似度: 0.916"));
        Assertions.assertFalse(markdown.contains("\n\nYD-338CC Series Product Overview"));
    }

    private RetrievedChunk visualChunk(String id, String imageUri, String sourceImage, Float score) {
        return RetrievedChunk.builder()
                .id(id)
                .text("<div><img src=\"" + sourceImage + "\" /></div><div>Fig. 1: Full View</div>")
                .score(score)
                .metadata(Map.of(
                        "content_type", "visual",
                        "block_id", "paddle-page-18",
                        "page_no", 18,
                        "image_uri", imageUri,
                        "markdown_images", Map.of(sourceImage, imageUri)
                ))
                .build();
    }

    private int countOccurrences(String value, String token) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
