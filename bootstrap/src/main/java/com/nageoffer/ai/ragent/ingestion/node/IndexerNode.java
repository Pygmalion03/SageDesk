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

package com.nageoffer.ai.ragent.ingestion.node;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.ingestion.domain.settings.IndexerSettings;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceId;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceSpec;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.response.InsertResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class IndexerNode implements IngestionNode {

    private static final Gson GSON = new Gson();
    private static final int MAX_DOC_ID_LENGTH = 36;

    private final ObjectMapper objectMapper;
    private final VectorStoreAdmin vectorStoreAdmin;
    private final MilvusClientV2 milvusClient;
    private final RAGDefaultProperties ragDefaultProperties;

    public IndexerNode(ObjectMapper objectMapper,
                       VectorStoreAdmin vectorStoreAdmin,
                       MilvusClientV2 milvusClient,
                       RAGDefaultProperties ragDefaultProperties) {
        this.objectMapper = objectMapper;
        this.vectorStoreAdmin = vectorStoreAdmin;
        this.milvusClient = milvusClient;
        this.ragDefaultProperties = ragDefaultProperties;
    }

    @Override
    public String getNodeType() {
        return IngestionNodeType.INDEXER.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        List<VectorChunk> chunks = context.getChunks();
        List<VectorChunk> visualChunks = context.getVisualChunks();
        boolean hasTextChunks = chunks != null && !chunks.isEmpty();
        boolean hasVisualChunks = visualChunks != null && !visualChunks.isEmpty();
        if (!hasTextChunks && !hasVisualChunks) {
            return NodeResult.fail(new ClientException("No chunks available for indexing"));
        }

        IndexerSettings settings = parseSettings(config.getSettings());

        int textRows = 0;
        int imageRows = 0;
        if (hasTextChunks) {
            String collectionName = resolveCollectionName(context);
            if (!StringUtils.hasText(collectionName)) {
                return NodeResult.fail(new ClientException("Collection name is required for indexing"));
            }
            textRows = indexChunks(
                    context,
                    chunks,
                    collectionName,
                    settings.getMetadataFields(),
                    ragDefaultProperties.getDimension(),
                    "RAG text vector space"
            );
        }

        if (Boolean.TRUE.equals(settings.getImageIndexEnabled()) && hasVisualChunks) {
            String imageCollectionName = resolveImageCollectionName(context, settings);
            imageRows = indexChunks(
                    context,
                    visualChunks,
                    imageCollectionName,
                    settings.getImageMetadataFields(),
                    ragDefaultProperties.getImageDimension(),
                    "RAG visual vector space"
            );
        }

        return NodeResult.ok("Indexed text chunks=" + textRows + ", visual chunks=" + imageRows);
    }

    private IndexerSettings parseSettings(JsonNode node) {
        if (node == null || node.isNull()) {
            return IndexerSettings.builder().build();
        }
        return objectMapper.convertValue(node, IndexerSettings.class);
    }

    private String resolveCollectionName(IngestionContext context) {
        if (context.getVectorSpaceId() != null && StringUtils.hasText(context.getVectorSpaceId().getLogicalName())) {
            return context.getVectorSpaceId().getLogicalName();
        }
        return ragDefaultProperties.getCollectionName();
    }

    private String resolveImageCollectionName(IngestionContext context, IndexerSettings settings) {
        if (StringUtils.hasText(settings.getImageCollectionName())) {
            return settings.getImageCollectionName();
        }
        return resolveCollectionName(context) + ragDefaultProperties.getImageCollectionSuffix();
    }

    private int indexChunks(IngestionContext context,
                            List<VectorChunk> chunks,
                            String collectionName,
                            List<String> metadataFields,
                            Integer configuredDim,
                            String remark) {
        int expectedDim = resolveDimension(chunks, configuredDim);
        if (expectedDim <= 0) {
            throw new ClientException("Vector dimension is missing");
        }
        float[][] vectorArray = toArrayFromChunks(chunks, expectedDim);
        ensureVectorSpace(collectionName, expectedDim, remark);
        List<JsonObject> rows = buildRows(context, chunks, vectorArray, metadataFields);
        insertRows(collectionName, rows);
        return rows.size();
    }

    private void ensureVectorSpace(String collectionName, int dimension, String remark) {
        boolean vectorSpaceExists = vectorStoreAdmin.vectorSpaceExists(VectorSpaceId.builder()
                .logicalName(collectionName)
                .build());
        if (vectorSpaceExists) {
            return;
        }

        VectorSpaceSpec spaceSpec = VectorSpaceSpec.builder()
                .spaceId(VectorSpaceId.builder()
                        .logicalName(collectionName)
                        .build())
                .remark(remark)
                .dimension(dimension)
                .metricType(ragDefaultProperties.getMetricType())
                .build();
        vectorStoreAdmin.ensureVectorSpace(spaceSpec);
    }

    private void insertRows(String collectionName, List<JsonObject> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        InsertReq req = InsertReq.builder()
                .collectionName(collectionName)
                .data(rows)
                .build();
        try {
            InsertResp resp = milvusClient.insert(req);
            log.info("Milvus insert success, collection={}, rows={}", collectionName, resp.getInsertCnt());
        } catch (Exception ex) {
            JsonObject firstRow = rows.get(0);
            log.error("Milvus insert failed, collection={}, firstRow={}", collectionName, truncate(firstRow.toString()), ex);
            throw ex;
        }
    }

    private int resolveDimension(List<VectorChunk> chunks, Integer configured) {
        if (configured != null && configured > 0) {
            return configured;
        }
        for (VectorChunk chunk : chunks) {
            if (chunk.getEmbedding() != null && chunk.getEmbedding().length > 0) {
                return chunk.getEmbedding().length;
            }
        }
        return 0;
    }

    private float[][] toArrayFromChunks(List<VectorChunk> chunks, int expectedDim) {
        float[][] out = new float[chunks.size()][];
        for (int i = 0; i < chunks.size(); i++) {
            float[] vector = chunks.get(i).getEmbedding();
            if (vector == null || vector.length == 0) {
                throw new ClientException("Vector is missing at chunk index " + i);
            }
            if (expectedDim > 0 && vector.length != expectedDim) {
                throw new ClientException("Vector dimension mismatch at chunk index " + i);
            }
            out[i] = vector;
        }
        return out;
    }

    private List<JsonObject> buildRows(IngestionContext context,
                                       List<VectorChunk> chunks,
                                       float[][] vectors,
                                       List<String> metadataFields) {
        Map<String, Object> mergedMetadata = mergeMetadata(context);
        List<JsonObject> rows = new java.util.ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            VectorChunk chunk = chunks.get(i);
            String rawChunkId = StringUtils.hasText(chunk.getChunkId()) ? chunk.getChunkId() : IdUtil.getSnowflakeNextIdStr();
            String chunkId = normalizeChunkId(rawChunkId);
            chunk.setChunkId(chunkId);
            chunk.setEmbedding(vectors[i]);

            String content = chunk.getContent() == null ? "" : chunk.getContent();
            if (content.length() > 65535) {
                content = content.substring(0, 65535);
            }

            JsonObject metadata = new JsonObject();
            metadata.addProperty("chunk_index", chunk.getIndex());
            metadata.addProperty("task_id", context.getTaskId());
            metadata.addProperty("pipeline_id", context.getPipelineId());
            DocumentSource source = context.getSource();
            if (source != null && source.getType() != null) {
                metadata.addProperty("source_type", source.getType().getValue());
            }
            if (source != null && StringUtils.hasText(source.getLocation())) {
                metadata.addProperty("source_location", source.getLocation());
            }
            if (!chunkId.equals(rawChunkId)) {
                metadata.addProperty("original_chunk_id", rawChunkId);
            }

            if (metadataFields != null && !metadataFields.isEmpty()) {
                Map<String, Object> combined = new HashMap<>(mergedMetadata);
                if (chunk.getMetadata() != null) {
                    combined.putAll(chunk.getMetadata());
                }
                for (String field : metadataFields) {
                    if (!StringUtils.hasText(field)) {
                        continue;
                    }
                    Object value = combined.get(field);
                    if (value != null) {
                        addMetadataValue(metadata, field, value);
                    }
                }
            } else if (chunk.getMetadata() != null && !chunk.getMetadata().isEmpty()) {
                chunk.getMetadata().forEach((field, value) -> {
                    if (StringUtils.hasText(field) && value != null) {
                        addMetadataValue(metadata, field, value);
                    }
                });
            }

            JsonObject row = new JsonObject();
            row.addProperty("doc_id", chunkId);
            row.addProperty("content", content);
            row.add("metadata", metadata);
            row.add("embedding", toJsonArray(vectors[i]));
            rows.add(row);
        }
        return rows;
    }

    private String normalizeChunkId(String rawChunkId) {
        if (!StringUtils.hasText(rawChunkId)) {
            return IdUtil.fastSimpleUUID();
        }
        if (rawChunkId.length() <= MAX_DOC_ID_LENGTH) {
            return rawChunkId;
        }
        return DigestUtil.md5Hex(rawChunkId);
    }

    private Map<String, Object> mergeMetadata(IngestionContext context) {
        Map<String, Object> merged = new HashMap<>();
        if (context.getMetadata() != null) {
            merged.putAll(context.getMetadata());
        }
        return merged;
    }

    private void addMetadataValue(JsonObject metadata, String field, Object value) {
        JsonElement element = GSON.toJsonTree(value);
        metadata.add(field, element);
    }

    private JsonArray toJsonArray(float[] vector) {
        JsonArray arr = new JsonArray(vector.length);
        for (float v : vector) {
            arr.add(v);
        }
        return arr;
    }

    private String truncate(String value) {
        if (!StringUtils.hasText(value) || value.length() <= 512) {
            return value;
        }
        return value.substring(0, 512) + "...";
    }
}
