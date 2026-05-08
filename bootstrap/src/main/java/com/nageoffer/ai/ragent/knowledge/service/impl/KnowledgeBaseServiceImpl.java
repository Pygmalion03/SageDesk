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

package com.nageoffer.ai.ragent.knowledge.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.infra.model.EmbeddingModelDimensionResolver;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseCreateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBasePageRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeBaseVO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeBaseService;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceId;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceSpec;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private static final Pattern COLLECTION_NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9]{2,49}$");
    private static final String COLLECTION_NAME_RULE_MESSAGE =
            "Collection 名称只能使用小写英文字母和数字，必须以字母开头，长度 3-50 个字符";

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final VectorStoreAdmin vectorStoreAdmin;
    private final S3Client s3Client;
    private final EmbeddingModelDimensionResolver dimensionResolver;

    @Transactional
    @Override
    public String create(KnowledgeBaseCreateRequest requestParam) {
        validateCreateRequest(requestParam);

        String name = requestParam.getName().replaceAll("\\s+", "");
        Long count = knowledgeBaseMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeBaseDO>()
                        .eq(KnowledgeBaseDO::getName, name)
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        if (count > 0) {
            throw new ServiceException("知识库名称已存在：" + requestParam.getName());
        }

        Long collectionCount = knowledgeBaseMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeBaseDO>()
                        .eq(KnowledgeBaseDO::getCollectionName, requestParam.getCollectionName())
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        if (collectionCount > 0) {
            throw new ClientException("Collection 名称已存在：" + requestParam.getCollectionName());
        }

        KnowledgeBaseDO kbDO = KnowledgeBaseDO.builder()
                .name(requestParam.getName())
                .embeddingModel(requestParam.getEmbeddingModel())
                .collectionName(requestParam.getCollectionName())
                .enabled(1)
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .deleted(0)
                .build();

        knowledgeBaseMapper.insert(kbDO);

        String bucketName = requestParam.getCollectionName();
        try {
            s3Client.createBucket(builder -> builder.bucket(bucketName));
            log.info("成功创建RestFS存储桶，Bucket名称: {}", bucketName);
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException e) {
            throw collectionNameOccupied(bucketName, e);
        } catch (S3Exception e) {
            if (isBucketAlreadyExists(e)) {
                throw collectionNameOccupied(bucketName, e);
            }
            throw new ServiceException(
                    "创建知识库存储空间失败，请检查 RustFS/S3 服务：" + bucketName,
                    e,
                    BaseErrorCode.SERVICE_ERROR
            );
        } catch (IllegalArgumentException e) {
            throw new ClientException(COLLECTION_NAME_RULE_MESSAGE, e, BaseErrorCode.CLIENT_ERROR);
        }

        VectorSpaceSpec spaceSpec = VectorSpaceSpec.builder()
                .spaceId(VectorSpaceId.builder()
                        .logicalName(requestParam.getCollectionName())
                        .build())
                .remark(requestParam.getName())
                .dimension(dimensionResolver.resolveDimension(requestParam.getEmbeddingModel()))
                .build();
        vectorStoreAdmin.ensureVectorSpace(spaceSpec);

        return String.valueOf(kbDO.getId());
    }

    @Override
    public void update(KnowledgeBaseUpdateRequest requestParam) {
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(requestParam.getId());
        if (kb == null || kb.getDeleted() != null && kb.getDeleted() == 1) {
            throw new IllegalArgumentException("知识库不存在：" + requestParam.getId());
        }

        if (StringUtils.hasText(requestParam.getEmbeddingModel())
                && !requestParam.getEmbeddingModel().equals(kb.getEmbeddingModel())) {

            Long docCount = knowledgeDocumentMapper.selectCount(
                    new LambdaQueryWrapper<KnowledgeDocumentDO>()
                            .eq(KnowledgeDocumentDO::getKbId, requestParam.getId())
                            .gt(KnowledgeDocumentDO::getChunkCount, 0)
                            .eq(KnowledgeDocumentDO::getDeleted, 0)
            );
            if (docCount > 0) {
                throw new IllegalStateException("知识库已存在向量化文档，不允许修改嵌入模型");
            }

            kb.setEmbeddingModel(requestParam.getEmbeddingModel());
        }

        if (StringUtils.hasText(requestParam.getName())) {
            kb.setName(requestParam.getName());
        }

        kb.setUpdatedBy(UserContext.getUsername());
        knowledgeBaseMapper.updateById(kb);
    }

    @Override
    public void rename(String kbId, KnowledgeBaseUpdateRequest requestParam) {
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null || kb.getDeleted() != null && kb.getDeleted() == 1) {
            throw new ClientException("知识库不存在");
        }

        if (!StringUtils.hasText(requestParam.getName())) {
            throw new ClientException("知识库名称不能为空");
        }

        String name = requestParam.getName().replaceAll("\\s+", "");
        Long count = knowledgeBaseMapper.selectCount(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                        .eq(KnowledgeBaseDO::getName, name)
                        .ne(KnowledgeBaseDO::getId, kbId)
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        if (count > 0) {
            throw new ServiceException("知识库名称已存在：" + requestParam.getName());
        }

        kb.setName(requestParam.getName());
        kb.setUpdatedBy(UserContext.getUsername());
        knowledgeBaseMapper.updateById(kb);

        log.info("成功重命名知识库, kbId={}, newName={}", kbId, requestParam.getName());
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void enable(String kbId, boolean enabled) {
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null || kb.getDeleted() != null && kb.getDeleted() == 1) {
            throw new ClientException("知识库不存在");
        }

        int enabledValue = enabled ? 1 : 0;
        String username = UserContext.getUsername();
        kb.setEnabled(enabledValue);
        kb.setUpdatedBy(username);
        knowledgeBaseMapper.updateById(kb);

        KnowledgeDocumentDO documentUpdate = new KnowledgeDocumentDO();
        documentUpdate.setEnabled(enabledValue);
        documentUpdate.setUpdatedBy(username);
        knowledgeDocumentMapper.update(
                documentUpdate,
                Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                        .eq(KnowledgeDocumentDO::getKbId, kb.getId())
                        .eq(KnowledgeDocumentDO::getDeleted, 0)
        );

        KnowledgeChunkDO chunkUpdate = new KnowledgeChunkDO();
        chunkUpdate.setEnabled(enabledValue);
        chunkUpdate.setUpdatedBy(username);
        knowledgeChunkMapper.update(
                chunkUpdate,
                Wrappers.lambdaUpdate(KnowledgeChunkDO.class)
                        .eq(KnowledgeChunkDO::getKbId, kb.getId())
                        .eq(KnowledgeChunkDO::getDeleted, 0)
        );
    }

    @Override
    public void delete(String kbId) {
        Long docCount = knowledgeDocumentMapper.selectCount(
                Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                        .eq(KnowledgeDocumentDO::getKbId, kbId)
                        .eq(KnowledgeDocumentDO::getDeleted, 0)
        );
        if (docCount > 0) {
            throw new ClientException("知识库下仍有关联文档，无法删除");
        }

        knowledgeBaseMapper.deleteById(kbId);
    }

    @Override
    public KnowledgeBaseVO queryById(String kbId) {
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(kbId);
        if (kbDO == null || kbDO.getDeleted() != null && kbDO.getDeleted() == 1) {
            throw new ClientException("知识库不存在");
        }
        Map<Long, KnowledgeBaseStats> statsMap = loadStats(List.of(kbDO.getId()));
        return toVO(kbDO, statsMap.get(kbDO.getId()));
    }

    @Override
    public IPage<KnowledgeBaseVO> pageQuery(KnowledgeBasePageRequest requestParam) {
        LambdaQueryWrapper<KnowledgeBaseDO> queryWrapper = Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                .like(StringUtils.hasText(requestParam.getName()), KnowledgeBaseDO::getName, requestParam.getName())
                .eq(KnowledgeBaseDO::getDeleted, 0)
                .orderByDesc(KnowledgeBaseDO::getUpdateTime);

        Page<KnowledgeBaseDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        IPage<KnowledgeBaseDO> result = knowledgeBaseMapper.selectPage(page, queryWrapper);
        Map<Long, KnowledgeBaseStats> statsMap = loadStats(result.getRecords().stream()
                .map(KnowledgeBaseDO::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));
        return result.convert(each -> toVO(each, statsMap.get(each.getId())));
    }

    private void validateCreateRequest(KnowledgeBaseCreateRequest requestParam) {
        if (requestParam == null) {
            throw new ClientException("创建知识库参数不能为空");
        }
        if (!StringUtils.hasText(requestParam.getName())) {
            throw new ClientException("知识库名称不能为空");
        }
        if (!StringUtils.hasText(requestParam.getEmbeddingModel())) {
            throw new ClientException("Embedding 模型不能为空");
        }
        if (!StringUtils.hasText(requestParam.getCollectionName())) {
            throw new ClientException("Collection 名称不能为空");
        }

        requestParam.setName(requestParam.getName().trim());
        requestParam.setEmbeddingModel(requestParam.getEmbeddingModel().trim());
        requestParam.setCollectionName(requestParam.getCollectionName().trim());

        if (!COLLECTION_NAME_PATTERN.matcher(requestParam.getCollectionName()).matches()) {
            throw new ClientException(COLLECTION_NAME_RULE_MESSAGE);
        }
    }

    private KnowledgeBaseVO toVO(KnowledgeBaseDO each, KnowledgeBaseStats stats) {
        KnowledgeBaseStats safeStats = stats == null ? KnowledgeBaseStats.EMPTY : stats;
        KnowledgeBaseVO vo = BeanUtil.toBean(each, KnowledgeBaseVO.class);
        boolean enabled = isEnabled(each.getEnabled());
        vo.setEnabled(enabled);
        vo.setDocumentCount(safeStats.documentCount());
        vo.setEnabledDocumentCount(safeStats.enabledDocumentCount());
        vo.setChunkCount(safeStats.chunkCount());
        vo.setEnabledChunkCount(safeStats.enabledChunkCount());
        vo.setEffectiveEnabled(enabled && safeStats.enabledChunkCount() > 0);
        return vo;
    }

    private Map<Long, KnowledgeBaseStats> loadStats(List<Long> kbIds) {
        if (CollUtil.isEmpty(kbIds)) {
            return Map.of();
        }
        Map<Long, Long> docCountMap = new HashMap<>();
        Map<Long, Long> enabledDocCountMap = new HashMap<>();
        Map<Long, Long> chunkCountMap = new HashMap<>();
        Map<Long, Long> enabledChunkCountMap = new HashMap<>();

        List<Map<String, Object>> docRows = knowledgeDocumentMapper.selectMaps(
                Wrappers.query(KnowledgeDocumentDO.class)
                        .select(
                                "kb_id AS kbId",
                                "COUNT(1) AS docCount",
                                "SUM(CASE WHEN enabled = 1 THEN 1 ELSE 0 END) AS enabledDocCount"
                        )
                        .in("kb_id", kbIds)
                        .eq("deleted", 0)
                        .groupBy("kb_id")
        );
        for (Map<String, Object> row : docRows) {
            Long kbId = parseLong(row.get("kbId"));
            if (kbId == null) {
                continue;
            }
            docCountMap.put(kbId, defaultLong(parseLong(row.get("docCount"))));
            enabledDocCountMap.put(kbId, defaultLong(parseLong(row.get("enabledDocCount"))));
        }

        List<Map<String, Object>> chunkRows = knowledgeChunkMapper.selectMaps(
                Wrappers.query(KnowledgeChunkDO.class)
                        .select(
                                "kb_id AS kbId",
                                "COUNT(1) AS chunkCount"
                        )
                        .in("kb_id", kbIds)
                        .eq("deleted", 0)
                        .groupBy("kb_id")
        );
        for (Map<String, Object> row : chunkRows) {
            Long kbId = parseLong(row.get("kbId"));
            if (kbId == null) {
                continue;
            }
            chunkCountMap.put(kbId, defaultLong(parseLong(row.get("chunkCount"))));
        }

        List<KnowledgeDocumentDO> enabledDocuments = knowledgeDocumentMapper.selectList(
                Wrappers.query(KnowledgeDocumentDO.class)
                        .select("id", "kb_id")
                        .in("kb_id", kbIds)
                        .eq("deleted", 0)
                        .eq("enabled", 1)
        );
        List<Long> enabledDocIds = CollUtil.emptyIfNull(enabledDocuments).stream()
                .map(KnowledgeDocumentDO::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (CollUtil.isNotEmpty(enabledDocIds)) {
            List<Map<String, Object>> enabledChunkRows = knowledgeChunkMapper.selectMaps(
                    Wrappers.query(KnowledgeChunkDO.class)
                            .select("kb_id AS kbId", "COUNT(1) AS enabledChunkCount")
                            .in("doc_id", enabledDocIds)
                            .eq("enabled", 1)
                            .eq("deleted", 0)
                            .groupBy("kb_id")
            );
            for (Map<String, Object> row : enabledChunkRows) {
                Long kbId = parseLong(row.get("kbId"));
                if (kbId == null) {
                    continue;
                }
                enabledChunkCountMap.put(kbId, defaultLong(parseLong(row.get("enabledChunkCount"))));
            }
        }

        Map<Long, KnowledgeBaseStats> result = new HashMap<>();
        for (Long kbId : kbIds) {
            result.put(kbId, new KnowledgeBaseStats(
                    defaultLong(docCountMap.get(kbId)),
                    defaultLong(enabledDocCountMap.get(kbId)),
                    defaultLong(chunkCountMap.get(kbId)),
                    defaultLong(enabledChunkCountMap.get(kbId))
            ));
        }
        return result;
    }

    private ClientException collectionNameOccupied(String bucketName, Exception cause) {
        return new ClientException(
                "Collection 名称对应的存储桶已存在，请换一个名称：" + bucketName,
                cause,
                BaseErrorCode.CLIENT_ERROR
        );
    }

    private boolean isBucketAlreadyExists(S3Exception e) {
        String errorCode = e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
        return e.statusCode() == 409
                || "BucketAlreadyExists".equals(errorCode)
                || "BucketAlreadyOwnedByYou".equals(errorCode);
    }

    private boolean isEnabled(Integer enabled) {
        return Integer.valueOf(1).equals(enabled);
    }

    private Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = value.toString();
        return StringUtils.hasText(text) ? Long.parseLong(text) : null;
    }

    private long defaultLong(Long value) {
        return value == null ? 0L : value;
    }

    private record KnowledgeBaseStats(long documentCount,
                                      long enabledDocumentCount,
                                      long chunkCount,
                                      long enabledChunkCount) {
        private static final KnowledgeBaseStats EMPTY = new KnowledgeBaseStats(0L, 0L, 0L, 0L);
    }
}
