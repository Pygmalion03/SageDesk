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

package com.nageoffer.ai.ragent.rag.core.vector;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.IdUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.infra.model.EmbeddingModelDimensionResolver;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.UpsertResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusVectorStoreService implements VectorStoreService {

    private final MilvusClientV2 milvusClient;
    private final KnowledgeBaseMapper kbMapper;
    private final RAGDefaultProperties ragDefaultProperties;
    private final EmbeddingModelDimensionResolver dimensionResolver;

    @Override
    public void indexDocumentChunks(String kbId, String docId, List<VectorChunk> chunks) {
        Assert.isFalse(chunks == null || chunks.isEmpty(), () -> new ClientException("文档分块不能为空"));

        KnowledgeBaseDO kbDO = kbMapper.selectById(kbId);
        Assert.isFalse(kbDO == null, () -> new ClientException("知识库不存在"));

        int dim = resolveExpectedDimension(kbDO);
        List<float[]> vectors = extractVectors(chunks, dim);

        List<JsonObject> rows = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            VectorChunk chunk = chunks.get(i);

            String content = chunk.getContent() == null ? "" : chunk.getContent();
            if (content.length() > 65535) {
                content = content.substring(0, 65535);
            }

            JsonObject metadata = new JsonObject();
            metadata.addProperty("kb_id", kbId);
            metadata.addProperty("doc_id", docId);
            metadata.addProperty("chunk_index", chunk.getIndex());

            JsonObject row = new JsonObject();
            row.addProperty("doc_id", chunk.getChunkId());
            row.addProperty("content", content);
            row.add("metadata", metadata);
            row.add("embedding", toJsonArray(vectors.get(i)));

            rows.add(row);
        }

        String collection = kbDO.getCollectionName();
        InsertReq req = InsertReq.builder()
                .collectionName(collection)
                .data(rows)
                .build();

        InsertResp resp = milvusClient.insert(req);
        log.info("Milvus chunk 向量写入成功, collection={}, rows={}", collection, resp.getInsertCnt());
    }

    @Override
    public void updateChunk(String kbId, String docId, VectorChunk chunk) {
        Assert.isFalse(chunk == null, () -> new ClientException("Chunk 不能为空"));

        KnowledgeBaseDO kbDO = kbMapper.selectById(kbId);
        Assert.isFalse(kbDO == null, () -> new ClientException("知识库不存在"));

        int dim = resolveExpectedDimension(kbDO);
        float[] vector = extractVector(chunk, dim);

        String chunkPk = chunk.getChunkId() != null ? chunk.getChunkId() : IdUtil.getSnowflakeNextIdStr();

        String content = chunk.getContent() == null ? "" : chunk.getContent();
        if (content.length() > 65535) {
            content = content.substring(0, 65535);
        }

        JsonObject metadata = new JsonObject();
        metadata.addProperty("kb_id", kbId);
        metadata.addProperty("doc_id", docId);
        metadata.addProperty("chunk_index", chunk.getIndex());

        JsonObject row = new JsonObject();
        row.addProperty("doc_id", chunkPk);
        row.addProperty("content", content);
        row.add("metadata", metadata);
        row.add("embedding", toJsonArray(vector));

        String collection = kbDO.getCollectionName();
        UpsertReq upsertReq = UpsertReq.builder()
                .collectionName(collection)
                .data(List.of(row))
                .build();

        UpsertResp resp = milvusClient.upsert(upsertReq);
        log.info("Milvus chunk 向量更新成功, collection={}, kbId={}, docId={}, chunkId={}, upsertCnt={}",
                collection, kbId, docId, chunkPk, resp.getUpsertCnt());
    }

    private List<float[]> extractVectors(List<VectorChunk> chunks, int expectedDim) {
        List<float[]> vectors = new ArrayList<>(chunks.size());
        for (VectorChunk chunk : chunks) {
            vectors.add(extractVector(chunk, expectedDim));
        }
        return vectors;
    }

    private float[] extractVector(VectorChunk chunk, int expectedDim) {
        float[] vector = chunk.getEmbedding();
        if (vector == null || vector.length == 0) {
            throw new ClientException("向量不能为空");
        }
        if (vector.length != expectedDim) {
            throw new ClientException("向量维度不匹配，期望维度为 " + expectedDim);
        }
        return vector;
    }

    private int resolveExpectedDimension(KnowledgeBaseDO kbDO) {
        Integer configuredDimension = kbDO == null ? null : dimensionResolver.resolveDimension(kbDO.getEmbeddingModel());
        if (configuredDimension != null && configuredDimension > 0) {
            return configuredDimension;
        }
        Integer defaultDimension = ragDefaultProperties.getDimension();
        return defaultDimension != null && defaultDimension > 0 ? defaultDimension : 4096;
    }

    @Override
    public void deleteDocumentVectors(String kbId, String docId) {
        KnowledgeBaseDO kbDO = kbMapper.selectById(kbId);
        Assert.notNull(kbDO, () -> new ClientException("知识库不存在"));

        String filter = "metadata[\"kb_id\"] == \"" + kbId + "\" && " +
                "metadata[\"doc_id\"] == \"" + docId + "\"";

        DeleteReq deleteReq = DeleteReq.builder()
                .collectionName(kbDO.getCollectionName())
                .filter(filter)
                .build();

        DeleteResp resp = milvusClient.delete(deleteReq);
        log.info("Milvus 文档向量删除成功, collection={}, kbId={}, docId={}, deleteCnt={}",
                kbDO.getCollectionName(), kbId, docId, resp.getDeleteCnt());
    }

    @Override
    public void deleteChunkById(String kbId, String chunkId) {
        KnowledgeBaseDO kbDO = kbMapper.selectById(kbId);
        Assert.isFalse(kbDO == null, () -> new ClientException("知识库不存在"));

        DeleteReq deleteReq = DeleteReq.builder()
                .collectionName(kbDO.getCollectionName())
                .filter("doc_id == \"" + chunkId + "\"")
                .build();

        DeleteResp resp = milvusClient.delete(deleteReq);
        log.info("Milvus chunk 向量删除成功, collection={}, kbId={}, chunkId={}, deleteCnt={}",
                kbDO.getCollectionName(), kbId, chunkId, resp.getDeleteCnt());
    }

    private JsonArray toJsonArray(float[] vector) {
        JsonArray arr = new JsonArray(vector.length);
        for (float value : vector) {
            arr.add(value);
        }
        return arr;
    }
}
