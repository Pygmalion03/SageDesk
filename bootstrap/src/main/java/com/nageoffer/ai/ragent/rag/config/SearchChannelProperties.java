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

@Data
@Component
@ConfigurationProperties(prefix = "rag.search")
public class SearchChannelProperties {

    private Channels channels = new Channels();

    private Rerank rerank = new Rerank();

    @Data
    public static class Channels {

        private VectorGlobal vectorGlobal = new VectorGlobal();

        private IntentDirected intentDirected = new IntentDirected();

        private IntentDirectedVisual intentDirectedVisual = new IntentDirectedVisual();

        private VisualGlobal visualGlobal = new VisualGlobal();
    }

    @Data
    public static class VectorGlobal {

        private boolean enabled = true;

        private double confidenceThreshold = 0.6;

        private boolean supplementIntentDirected = true;

        private int topKMultiplier = 3;
    }

    @Data
    public static class IntentDirected {

        private boolean enabled = true;

        private double minIntentScore = 0.4;

        private int topKMultiplier = 2;
    }

    @Data
    public static class IntentDirectedVisual {

        private boolean enabled = true;

        private int topKMultiplier = 2;

        private String embeddingModel = "qwen3-vl-embedding-2b-local";
    }

    @Data
    public static class VisualGlobal {

        private boolean enabled = false;

        private int topKMultiplier = 2;

        private String embeddingModel = "qwen3-vl-embedding-2b-local";

        private String rerankModel = "rerank-noop";
    }

    @Data
    public static class Rerank {

        private boolean enabled = true;

        private int candidateMultiplier = 3;

        private int maxCandidates = 12;

        private boolean filterVisualNavigationNoise = true;
    }
}
