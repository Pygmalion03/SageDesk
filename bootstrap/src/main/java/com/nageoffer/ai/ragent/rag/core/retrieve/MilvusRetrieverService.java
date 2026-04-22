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

package com.nageoffer.ai.ragent.rag.core.retrieve;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusRetrieverService implements RetrieverService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final EmbeddingService embeddingService;
    private final MilvusClientV2 milvusClient;
    private final RAGDefaultProperties ragDefaultProperties;
    private final ObjectMapper objectMapper;

    @Override
    public List<RetrievedChunk> retrieve(RetrieveRequest retrieveParam) {
        List<Float> emb = StringUtils.hasText(retrieveParam.getEmbeddingModel())
                ? embeddingService.embed(retrieveParam.getQuery(), retrieveParam.getEmbeddingModel())
                : embeddingService.embed(retrieveParam.getQuery());
        float[] vec = toArray(emb);
        float[] norm = normalize(vec);
        return retrieveByVector(norm, retrieveParam);
    }

    @Override
    public List<RetrievedChunk> retrieveByVector(float[] vector, RetrieveRequest retrieveParam) {
        List<BaseVector> vectors = List.of(new FloatVec(vector));

        Map<String, Object> params = new HashMap<>();
        params.put("metric_type", ragDefaultProperties.getMetricType());
        params.put("ef", 128);

        SearchReq req = SearchReq.builder()
                .collectionName(
                        StrUtil.isBlank(retrieveParam.getCollectionName()) ? ragDefaultProperties.getCollectionName() : retrieveParam.getCollectionName()
                )
                .annsField("embedding")
                .data(vectors)
                .topK(retrieveParam.getTopK())
                .searchParams(params)
                .outputFields(List.of("doc_id", "content", "metadata"))
                .build();

        SearchResp resp = milvusClient.search(req);
        List<List<SearchResp.SearchResult>> results = resp.getSearchResults();

        if (results == null || results.isEmpty()) {
            return List.of();
        }

        return results.get(0).stream()
                .map(r -> new RetrievedChunk(
                        Objects.toString(r.getEntity().get("doc_id"), ""),
                        Objects.toString(r.getEntity().get("content"), ""),
                        r.getScore(),
                        extractMetadata(r.getEntity().get("metadata"))
                ))
                .collect(Collectors.toList());
    }

    private Map<String, Object> extractMetadata(Object rawMetadata) {
        if (rawMetadata instanceof Map<?, ?> map) {
            Map<String, Object> metadata = new HashMap<>();
            map.forEach((key, value) -> {
                if (key != null) {
                    metadata.put(String.valueOf(key), value);
                }
            });
            return metadata;
        }
        if (rawMetadata instanceof CharSequence text && StrUtil.isNotBlank(text.toString())) {
            try {
                return objectMapper.readValue(text.toString(), MAP_TYPE);
            } catch (Exception ignored) {
                // fallback to toString parsing below
            }
        }
        try {
            return objectMapper.readValue(String.valueOf(rawMetadata), MAP_TYPE);
        } catch (Exception ignored) {
            // ignore
        }
        return new HashMap<>();
    }

    private static float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private static float[] normalize(float[] v) {
        double sum = 0.0;
        for (float x : v) {
            sum += x * x;
        }
        double len = Math.sqrt(sum);
        float[] nv = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            nv[i] = (float) (v[i] / len);
        }
        return nv;
    }
}
