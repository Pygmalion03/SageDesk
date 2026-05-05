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

package com.nageoffer.ai.ragent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "rag.document-analysis")
public class DocumentAnalysisProperties {

    private boolean enabled = false;

    private boolean autoDetect = true;

    private boolean fallbackToTikaOnError = true;

    private String provider = "official";

    private String requestMode = "sync";

    private String baseUrl = "http://localhost:8099";

    private String endpoint = "/v1/document-analysis";

    private String syncUrl = "https://z9y7xan5l6yevav5.aistudio-app.com/layout-parsing";

    private String asyncJobUrl = "https://paddleocr.aistudio-app.com/api/v2/ocr/jobs";

    private String model = "PaddleOCR-VL-1.5-0.9B";

    private String apiKey;

    private String defaultMode = "paddleocr_vl_1_5";

    private String fallbackMode = "pp_structure_v3";

    private long asyncPollIntervalMs = 5000L;

    private long asyncTimeoutMs = 600000L;

    private int asyncMaxConcurrentJobs = 2;

    private int asyncSubmitMaxAttempts = 3;

    private long asyncSubmitRetryInitialDelayMs = 3000L;

    private long asyncSubmitRetryMaxDelayMs = 30000L;

    private String resultDownloadDir = "scripts/paddle_api_runtime";

    private boolean downloadRemoteImages = true;

    private boolean useDocOrientationClassify = false;

    private boolean useDocUnwarping = false;

    private boolean useChartRecognition = false;

    private List<String> autoMimeTypes = List.of("PDF", "IMAGE");
}
