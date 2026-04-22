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

package com.nageoffer.ai.ragent.rag.retrieve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.core.retrieve.MilvusRetrieverService;
import io.milvus.v2.client.MilvusClientV2;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.mockito.Mockito.mock;

class MilvusRetrieverServiceTests {

    @Test
    @SuppressWarnings("unchecked")
    void shouldParseJsonMetadataReturnedByMilvus() {
        MilvusRetrieverService service = new MilvusRetrieverService(
                mock(EmbeddingService.class),
                mock(MilvusClientV2.class),
                new RAGDefaultProperties(),
                new ObjectMapper()
        );

        Map<String, Object> metadata = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                service,
                "extractMetadata",
                "{\"content_type\":\"visual\",\"image_uri\":\"s3://rag-media/chart.png\",\"page_no\":7}"
        );

        Assertions.assertEquals("visual", metadata.get("content_type"));
        Assertions.assertEquals("s3://rag-media/chart.png", metadata.get("image_uri"));
        Assertions.assertEquals(7, metadata.get("page_no"));
    }
}
