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
    void shouldUseSourcePageImageWhenVisualCandidateIsLocalCrop() {
        MediaPreviewService mediaPreviewService = mock(MediaPreviewService.class);
        when(mediaPreviewService.buildPreviewUrl(anyString()))
                .thenAnswer(invocation -> "/preview?uri=" + invocation.getArgument(0));
        RAGDefaultProperties defaults = new RAGDefaultProperties();
        defaults.setVisualAnswerImageLimit(4);
        VisualAnswerAppendixService service = new VisualAnswerAppendixService(mediaPreviewService, defaults);

        RetrievedChunk tableCrop = RetrievedChunk.builder()
                .id("yd338cc-table")
                .text("YD-338CC Series Technical Specifications Feed Width Paper Capacity Price USD")
                .score(0.88f)
                .metadata(Map.of(
                        "content_type", "visual",
                        "block_type", "table",
                        "image_uri", "E:/Projects/ragent/scripts/paddle_bridge_runtime/crops/page-block-12.png",
                        "source_location", "s3://yuedujpg3/full-page.jpg",
                        "source_page_image", "E:/Projects/ragent/scripts/paddle_bridge_runtime/inputs/full-page.jpg",
                        "page_no", 1
                ))
                .build();

        String markdown = service.buildMarkdown(Map.of("visual", List.of(tableCrop)));

        Assertions.assertTrue(markdown.contains("s3://yuedujpg3/full-page.jpg"));
        Assertions.assertFalse(markdown.contains("page-block-12.png"));
    }

    @Test
    void shouldDeduplicateVisualChunksFromSamePageWhenRichPageEvidenceExists() {
        MediaPreviewService mediaPreviewService = mock(MediaPreviewService.class);
        when(mediaPreviewService.buildPreviewUrl(anyString()))
                .thenAnswer(invocation -> "/preview?uri=" + invocation.getArgument(0));
        RAGDefaultProperties defaults = new RAGDefaultProperties();
        defaults.setVisualAnswerImageLimit(4);
        VisualAnswerAppendixService service = new VisualAnswerAppendixService(mediaPreviewService, defaults);

        RetrievedChunk richPage = RetrievedChunk.builder()
                .id("page-18")
                .text("""
                        YD-338CC Series Product Overview
                        Technical Specifications Model Feed Width Cut Size Paper Capacity Card Capacity Disc Capacity Price USD
                        """)
                .score(0.77f)
                .metadata(Map.of(
                        "content_type", "visual",
                        "block_id", "paddle-page-18",
                        "task_id", "task-a",
                        "image_uri", "E:/pages/page-18.png",
                        "page_no", 18
                ))
                .build();
        RetrievedChunk samePageCrop = RetrievedChunk.builder()
                .id("crop-18")
                .text("<div>Fig. 1: Full View</div>")
                .score(0.75f)
                .metadata(Map.of(
                        "content_type", "visual",
                        "task_id", "task-a",
                        "image_uri", "s3://rag-media/page-18-crop.jpg",
                        "page_no", 18
                ))
                .build();

        String markdown = service.buildMarkdown(Map.of("visual", List.of(richPage, samePageCrop)));

        Assertions.assertEquals(1, countOccurrences(markdown, "](<"));
        Assertions.assertTrue(markdown.contains("page-18.png"));
        Assertions.assertFalse(markdown.contains("page-18-crop.jpg"));
    }

    @Test
    void shouldNotDeduplicateDifferentSourceDocumentsWithSamePageNumber() {
        MediaPreviewService mediaPreviewService = mock(MediaPreviewService.class);
        when(mediaPreviewService.buildPreviewUrl(anyString()))
                .thenAnswer(invocation -> "/preview?uri=" + invocation.getArgument(0));
        RAGDefaultProperties defaults = new RAGDefaultProperties();
        defaults.setVisualAnswerImageLimit(4);
        VisualAnswerAppendixService service = new VisualAnswerAppendixService(mediaPreviewService, defaults);

        RetrievedChunk firstDocumentPageOne = RetrievedChunk.builder()
                .id("doc-a-page-1")
                .text("YD-338CC Series Product Overview Technical Specifications Feed Width Price USD")
                .score(0.91f)
                .metadata(Map.of(
                        "content_type", "visual",
                        "task_id", "doc-a",
                        "source_location", "s3://yuedujpg3/doc-a.jpg",
                        "image_uri", "s3://yuedujpg3/doc-a.jpg",
                        "page_no", 1
                ))
                .build();
        RetrievedChunk secondDocumentPageOne = RetrievedChunk.builder()
                .id("doc-b-page-1")
                .text("YD-3120 Series Product Overview Technical Specifications Feed Width Price USD")
                .score(0.89f)
                .metadata(Map.of(
                        "content_type", "visual",
                        "task_id", "doc-b",
                        "source_location", "s3://yuedujpg3/doc-b.jpg",
                        "image_uri", "s3://yuedujpg3/doc-b.jpg",
                        "page_no", 1
                ))
                .build();

        String markdown = service.buildMarkdown(Map.of("visual", List.of(firstDocumentPageOne, secondDocumentPageOne)));

        Assertions.assertTrue(markdown.contains("doc-a.jpg"));
        Assertions.assertTrue(markdown.contains("doc-b.jpg"));
    }

    @Test
    void shouldDropLowScoreVisualOutliersWhenStrongVisualEvidenceExists() {
        MediaPreviewService mediaPreviewService = mock(MediaPreviewService.class);
        when(mediaPreviewService.buildPreviewUrl(anyString()))
                .thenAnswer(invocation -> "/preview?uri=" + invocation.getArgument(0));
        RAGDefaultProperties defaults = new RAGDefaultProperties();
        defaults.setVisualAnswerImageLimit(4);
        VisualAnswerAppendixService service = new VisualAnswerAppendixService(mediaPreviewService, defaults);

        RetrievedChunk productPage = RetrievedChunk.builder()
                .id("page-18")
                .text("YD-338CC Series Product Overview Technical Specifications Paper Capacity Disc Capacity Price USD")
                .score(0.77f)
                .metadata(Map.of(
                        "content_type", "visual",
                        "image_uri", "E:/pages/page-18.png",
                        "page_no", 18
                ))
                .build();
        RetrievedChunk unrelatedPage = RetrievedChunk.builder()
                .id("page-3")
                .text("Book / Paper / Hard Drive Shredding Effectiveness 1*1mm Powder Form")
                .score(0.26f)
                .metadata(Map.of(
                        "content_type", "visual",
                        "image_uri", "E:/pages/page-3.png",
                        "page_no", 3
                ))
                .build();

        String markdown = service.buildMarkdown(Map.of("visual", List.of(productPage, unrelatedPage)));

        Assertions.assertEquals(1, countOccurrences(markdown, "](<"));
        Assertions.assertTrue(markdown.contains("page-18.png"));
        Assertions.assertFalse(markdown.contains("page-3.png"));
    }

    @Test
    void shouldSuppressSingleVeryLowScoreVisualCandidate() {
        MediaPreviewService mediaPreviewService = mock(MediaPreviewService.class);
        when(mediaPreviewService.buildPreviewUrl(anyString()))
                .thenAnswer(invocation -> "/preview?uri=" + invocation.getArgument(0));
        RAGDefaultProperties defaults = new RAGDefaultProperties();
        defaults.setVisualAnswerImageLimit(4);
        VisualAnswerAppendixService service = new VisualAnswerAppendixService(mediaPreviewService, defaults);

        RetrievedChunk weakCandidate = RetrievedChunk.builder()
                .id("page-1")
                .text("Technical Specifications")
                .score(0.001f)
                .metadata(Map.of(
                        "content_type", "visual",
                        "image_uri", "E:/pages/page-1-technical-specifications-crop.png",
                        "page_no", 1
                ))
                .build();

        String markdown = service.buildMarkdown(Map.of("visual", List.of(weakCandidate)));

        Assertions.assertEquals("", markdown);
    }

    @Test
    void shouldPreferCleanPageImageOverLayoutDetectionOverlayFromSamePage() {
        MediaPreviewService mediaPreviewService = mock(MediaPreviewService.class);
        when(mediaPreviewService.buildPreviewUrl(anyString()))
                .thenAnswer(invocation -> "/preview?uri=" + invocation.getArgument(0));
        RAGDefaultProperties defaults = new RAGDefaultProperties();
        defaults.setVisualAnswerImageLimit(4);
        VisualAnswerAppendixService service = new VisualAnswerAppendixService(mediaPreviewService, defaults);

        RetrievedChunk debugOverlay = RetrievedChunk.builder()
                .id("layout-det-page-18")
                .text("YD-338CC Series Product Overview Technical Specifications Feed Width Paper Capacity Price USD")
                .score(0.997f)
                .metadata(Map.of(
                        "content_type", "visual",
                        "block_id", "paddle-page-18",
                        "image_uri", "E:/tmp/paddle_api_runtime/page-18/output/layout_det_res.jpg",
                        "page_no", 18
                ))
                .build();
        RetrievedChunk cleanPage = RetrievedChunk.builder()
                .id("clean-page-18")
                .text("YD-338CC Series Product Overview Technical Specifications Feed Width Paper Capacity Price USD")
                .score(0.994f)
                .metadata(Map.of(
                        "content_type", "visual",
                        "block_id", "paddle-page-18-clean",
                        "image_uri", "E:/tmp/paddle_api_runtime/page-18/page.png",
                        "page_no", 18
                ))
                .build();

        String markdown = service.buildMarkdown(Map.of("visual", List.of(debugOverlay, cleanPage)));

        Assertions.assertEquals(1, countOccurrences(markdown, "](<"));
        Assertions.assertTrue(markdown.contains("page.png"));
        Assertions.assertFalse(markdown.contains("layout_det_res.jpg"));
    }

    @Test
    void shouldSuppressPaddleVisResultOverlayEvenWithoutCleanCandidate() {
        MediaPreviewService mediaPreviewService = mock(MediaPreviewService.class);
        when(mediaPreviewService.buildPreviewUrl(anyString()))
                .thenAnswer(invocation -> "/preview?uri=" + invocation.getArgument(0));
        RAGDefaultProperties defaults = new RAGDefaultProperties();
        defaults.setVisualAnswerImageLimit(4);
        VisualAnswerAppendixService service = new VisualAnswerAppendixService(mediaPreviewService, defaults);

        RetrievedChunk debugOverlay = RetrievedChunk.builder()
                .id("vis-result-page-18")
                .text("YD-338CC Series Product Overview Technical Specifications Feed Width Paper Capacity Price USD")
                .score(0.997f)
                .metadata(Map.of(
                        "content_type", "visual",
                        "block_id", "paddle-page-18",
                        "image_uri", "E:/Projects/ragent/scripts/paddle_bridge_runtime/output/vis_result/page-18_annotated.png",
                        "page_no", 18
                ))
                .build();

        String markdown = service.buildMarkdown(Map.of("visual", List.of(debugOverlay)));

        Assertions.assertEquals("", markdown);
    }

    @Test
    void shouldSuppressGenericCategoryVisualWhenSpecificRichProductPageExists() {
        MediaPreviewService mediaPreviewService = mock(MediaPreviewService.class);
        when(mediaPreviewService.buildPreviewUrl(anyString()))
                .thenAnswer(invocation -> "/preview?uri=" + invocation.getArgument(0));
        RAGDefaultProperties defaults = new RAGDefaultProperties();
        defaults.setVisualAnswerImageLimit(4);
        VisualAnswerAppendixService service = new VisualAnswerAppendixService(mediaPreviewService, defaults);

        RetrievedChunk productPage = RetrievedChunk.builder()
                .id("page-18")
                .text("""
                        YD-338CC Series Product Overview
                        Technical Specifications Model Feed Width Cut Size Paper Capacity Card Capacity Disc Capacity Price USD
                        """)
                .score(0.77f)
                .metadata(Map.of(
                        "content_type", "visual",
                        "image_uri", "E:/pages/page-18.png",
                        "page_no", 18
                ))
                .build();
        RetrievedChunk genericCategoryPage = RetrievedChunk.builder()
                .id("page-8")
                .text("""
                        High-Power Office Shredders
                        Overview suitable for large offices, administrative departments and enterprises.
                        Representative models: YD-23026, YD-3120, YD-418CC, YD-428CC, YD-310CC26, YD-338CC
                        Quick Selection Front View Feeding Inlet Model Features Price USD
                        """)
                .score(0.978f)
                .metadata(Map.of(
                        "content_type", "visual",
                        "image_uri", "E:/pages/page-8.png",
                        "page_no", 8
                ))
                .build();

        String markdown = service.buildMarkdown(Map.of("visual", List.of(productPage, genericCategoryPage)));

        Assertions.assertEquals(1, countOccurrences(markdown, "](<"));
        Assertions.assertTrue(markdown.contains("page-18.png"));
        Assertions.assertFalse(markdown.contains("page-8.png"));
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
