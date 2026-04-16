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

package com.nageoffer.ai.ragent.ingestion.service.impl;

import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionTaskCreateRequest;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionTaskNodeVO;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionTaskVO;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionTaskDO;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionTaskNodeDO;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionTaskMapper;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionTaskNodeMapper;
import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.context.NodeLog;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionStatus;
import com.nageoffer.ai.ragent.ingestion.domain.enums.SourceType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import com.nageoffer.ai.ragent.ingestion.domain.result.IngestionResult;
import com.nageoffer.ai.ragent.ingestion.engine.IngestionEngine;
import com.nageoffer.ai.ragent.ingestion.service.IngestionPipelineService;
import com.nageoffer.ai.ragent.ingestion.service.IngestionTaskService;
import com.nageoffer.ai.ragent.ingestion.util.MimeTypeDetector;
import com.nageoffer.ai.ragent.rag.controller.request.DocumentSourceRequest;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Service
@RequiredArgsConstructor
@Slf4j
public class IngestionTaskServiceImpl implements IngestionTaskService {

    private static final String TASK_ACCEPTED_MESSAGE = "Task accepted and queued for background execution";

    private static final String TASK_QUEUE_REJECTED_MESSAGE = "Ingestion task queue is busy, please retry later";

    private static final String TASK_RECOVERY_FAILED_PREFIX = "Failed to recover interrupted ingestion task: ";

    private static final String INTERNAL_METADATA_PREFIX = "__ingestion_internal__";

    private static final String INTERNAL_STAGED_FILE_PATH = INTERNAL_METADATA_PREFIX + "stagedFilePath";

    private static final String INTERNAL_MIME_TYPE = INTERNAL_METADATA_PREFIX + "mimeType";

    private static final String INTERNAL_SOURCE_CREDENTIALS = INTERNAL_METADATA_PREFIX + "sourceCredentials";

    private static final String INTERNAL_VECTOR_SPACE_ID = INTERNAL_METADATA_PREFIX + "vectorSpaceId";

    private static final Path UPLOAD_STAGING_ROOT = Paths.get("scripts", "ingestion_task_runtime", "uploads");

    private final IngestionEngine engine;
    private final IngestionPipelineService pipelineService;
    private final IngestionTaskMapper taskMapper;
    private final IngestionTaskNodeMapper taskNodeMapper;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;
    @Qualifier("ingestionTaskExecutor")
    private final Executor ingestionTaskExecutor;

    @Override
    public IngestionResult execute(IngestionTaskCreateRequest request) {
        Assert.notNull(request, () -> new ClientException("Request must not be null"));
        DocumentSource source = toSource(request.getSource());
        return enqueueTask(request.getPipelineId(), source, null, null, request.getVectorSpaceId(), request.getMetadata());
    }

    @Override
    public IngestionResult upload(String pipelineId, MultipartFile file) {
        Assert.notNull(file, () -> new ClientException("File must not be null"));
        try {
            byte[] bytes = file.getBytes();
            String fileName = file.getOriginalFilename();
            if (!StringUtils.hasText(fileName)) {
                fileName = "upload.bin";
            }
            String mimeType = MimeTypeDetector.detect(bytes, fileName);
            DocumentSource source = DocumentSource.builder()
                    .type(SourceType.FILE)
                    .location(fileName)
                    .fileName(fileName)
                    .build();
            return enqueueTask(pipelineId, source, bytes, mimeType, null, null);
        } catch (Exception e) {
            throw new ClientException("Failed to read uploaded file: " + e.getMessage());
        }
    }

    @Override
    public IngestionTaskVO get(String taskId) {
        IngestionTaskDO task = taskMapper.selectById(taskId);
        Assert.notNull(task, () -> new ClientException("Ingestion task not found"));
        return toVO(task);
    }

    @Override
    public IPage<IngestionTaskVO> page(Page<IngestionTaskVO> page, String status) {
        Page<IngestionTaskDO> mpPage = new Page<>(page.getCurrent(), page.getSize());
        String normalizedStatus = normalizeStatus(status);
        LambdaQueryWrapper<IngestionTaskDO> queryWrapper = new LambdaQueryWrapper<IngestionTaskDO>()
                .eq(IngestionTaskDO::getDeleted, 0)
                .eq(StringUtils.hasText(normalizedStatus), IngestionTaskDO::getStatus, normalizedStatus)
                .orderByDesc(IngestionTaskDO::getCreateTime);
        IPage<IngestionTaskDO> result = taskMapper.selectPage(mpPage, queryWrapper);
        Page<IngestionTaskVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(this::toVO).toList());
        return voPage;
    }

    @Override
    public List<IngestionTaskNodeVO> listNodes(String taskId) {
        LambdaQueryWrapper<IngestionTaskNodeDO> queryWrapper = new LambdaQueryWrapper<IngestionTaskNodeDO>()
                .eq(IngestionTaskNodeDO::getDeleted, 0)
                .eq(IngestionTaskNodeDO::getTaskId, taskId)
                .orderByAsc(IngestionTaskNodeDO::getNodeOrder)
                .orderByAsc(IngestionTaskNodeDO::getId);
        List<IngestionTaskNodeDO> nodes = taskNodeMapper.selectList(queryWrapper);
        return nodes.stream().map(this::toNodeVO).toList();
    }

    @Override
    public void recoverInterruptedTasks() {
        List<IngestionTaskDO> candidates = listRecoverableTasks();
        if (candidates.isEmpty()) {
            return;
        }
        int resumed = 0;
        int failed = 0;
        for (IngestionTaskDO candidate : candidates) {
            try {
                PreparedTask preparedTask = rebuildPreparedTask(candidate);
                String rejectionMessage = submitBackgroundExecution(preparedTask);
                if (rejectionMessage != null) {
                    failed++;
                    continue;
                }
                resumed++;
            } catch (Exception ex) {
                failed++;
                String operator = resolveOperator(candidate.getUpdatedBy(), candidate.getCreatedBy());
                String message = TASK_RECOVERY_FAILED_PREFIX + ex.getMessage();
                log.error("Failed to recover interrupted ingestion task taskId={}, pipelineId={}",
                        candidate.getId(), candidate.getPipelineId(), ex);
                markTaskFailed(candidate.getId(), operator, message);
                cleanupStagedFile(readRawMap(candidate.getMetadataJson()));
            }
        }
        log.info("Interrupted ingestion recovery finished, resumed={}, failed={}", resumed, failed);
    }

    private IngestionResult enqueueTask(String pipelineId,
                                        DocumentSource source,
                                        byte[] rawBytes,
                                        String mimeType,
                                        VectorSpaceId vectorSpaceId,
                                        Map<String, Object> requestMetadata) {
        PreparedTask preparedTask = createPendingTask(pipelineId, source, rawBytes, mimeType, vectorSpaceId, requestMetadata);
        String rejectionMessage = submitBackgroundExecution(preparedTask);
        if (rejectionMessage != null) {
            return failedResult(preparedTask, rejectionMessage);
        }
        return acceptedResult(preparedTask);
    }

    private PreparedTask createPendingTask(String pipelineId,
                                           DocumentSource source,
                                           byte[] rawBytes,
                                           String mimeType,
                                           VectorSpaceId vectorSpaceId,
                                           Map<String, Object> requestMetadata) {
        Map<String, Object> metadataCopy = buildPersistentMetadata(requestMetadata, source, rawBytes, mimeType, vectorSpaceId);
        try {
            TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
            return txTemplate.execute(status -> {
                String resolvedPipelineId = resolvePipelineId(pipelineId);
                pipelineService.getDefinition(resolvedPipelineId);
                String operator = resolveOperator();
                IngestionTaskDO task = IngestionTaskDO.builder()
                        .pipelineId(Long.parseLong(resolvedPipelineId))
                        .sourceType(source.getType() == null ? null : source.getType().getValue())
                        .sourceLocation(source.getLocation())
                        .sourceFileName(source.getFileName())
                        .status(IngestionStatus.PENDING.getValue())
                        .chunkCount(0)
                        .metadataJson(writeJson(metadataCopy))
                        .createdBy(operator)
                        .updatedBy(operator)
                        .build();
                taskMapper.insert(task);
                return new PreparedTask(
                        task.getId(),
                        resolvedPipelineId,
                        copySource(source),
                        copyBytes(rawBytes),
                        mimeType,
                        vectorSpaceId,
                        metadataCopy,
                        operator
                );
            });
        } catch (RuntimeException ex) {
            cleanupStagedFile(metadataCopy);
            throw ex;
        }
    }

    private String submitBackgroundExecution(PreparedTask preparedTask) {
        try {
            ingestionTaskExecutor.execute(() -> runTask(preparedTask));
            return null;
        } catch (RejectedExecutionException ex) {
            log.warn("Ingestion task queue rejected taskId={}, pipelineId={}",
                    preparedTask.taskId(), preparedTask.pipelineId(), ex);
            markTaskFailed(preparedTask.taskId(), preparedTask.operator(), TASK_QUEUE_REJECTED_MESSAGE);
            cleanupStagedFile(preparedTask.metadata());
            return TASK_QUEUE_REJECTED_MESSAGE;
        } catch (RuntimeException ex) {
            String message = "Failed to schedule ingestion task: " + ex.getMessage();
            log.error("Ingestion task submit failed taskId={}, pipelineId={}",
                    preparedTask.taskId(), preparedTask.pipelineId(), ex);
            markTaskFailed(preparedTask.taskId(), preparedTask.operator(), message);
            cleanupStagedFile(preparedTask.metadata());
            return message;
        }
    }

    private void runTask(PreparedTask preparedTask) {
        PipelineDefinition pipeline = null;
        boolean terminalStatePersisted = false;
        try {
            markTaskRunning(preparedTask.taskId(), preparedTask.operator());
            pipeline = pipelineService.getDefinition(preparedTask.pipelineId());
            IngestionContext context = buildContext(preparedTask);
            IngestionContext result = engine.execute(pipeline, context);
            persistTaskResult(preparedTask, pipeline, result);
            terminalStatePersisted = true;
        } catch (Exception ex) {
            log.error("Background ingestion task failed taskId={}, pipelineId={}",
                    preparedTask.taskId(), preparedTask.pipelineId(), ex);
            try {
                persistTaskResult(preparedTask, pipeline, buildFailedContext(preparedTask, ex));
                terminalStatePersisted = true;
            } catch (Exception persistEx) {
                log.error("Failed to persist ingestion task terminal state taskId={}, pipelineId={}",
                        preparedTask.taskId(), preparedTask.pipelineId(), persistEx);
            }
        } finally {
            if (terminalStatePersisted) {
                cleanupStagedFile(preparedTask.metadata());
            }
        }
    }

    private IngestionContext buildContext(PreparedTask preparedTask) {
        return IngestionContext.builder()
                .taskId(String.valueOf(preparedTask.taskId()))
                .pipelineId(preparedTask.pipelineId())
                .source(copySource(preparedTask.source()))
                .rawBytes(copyBytes(preparedTask.rawBytes()))
                .mimeType(preparedTask.mimeType())
                .vectorSpaceId(preparedTask.vectorSpaceId())
                .metadata(copyMetadata(preparedTask.metadata()))
                .logs(new ArrayList<>())
                .build();
    }

    private IngestionContext buildFailedContext(PreparedTask preparedTask, Exception ex) {
        return IngestionContext.builder()
                .taskId(String.valueOf(preparedTask.taskId()))
                .pipelineId(preparedTask.pipelineId())
                .source(copySource(preparedTask.source()))
                .mimeType(preparedTask.mimeType())
                .vectorSpaceId(preparedTask.vectorSpaceId())
                .metadata(copyMetadata(preparedTask.metadata()))
                .status(IngestionStatus.FAILED)
                .logs(new ArrayList<>())
                .error(ex)
                .build();
    }

    private void markTaskRunning(Long taskId, String operator) {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.executeWithoutResult(status -> {
            IngestionTaskDO update = IngestionTaskDO.builder()
                    .id(taskId)
                    .status(IngestionStatus.RUNNING.getValue())
                    .startedAt(new Date())
                    .errorMessage(null)
                    .completedAt(null)
                    .updatedBy(operator)
                    .build();
            taskMapper.updateById(update);
        });
    }

    private void persistTaskResult(PreparedTask preparedTask, PipelineDefinition pipeline, IngestionContext context) {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.executeWithoutResult(status -> {
            taskNodeMapper.delete(new LambdaQueryWrapper<IngestionTaskNodeDO>()
                    .eq(IngestionTaskNodeDO::getTaskId, preparedTask.taskId()));
            IngestionTaskDO task = IngestionTaskDO.builder()
                    .id(preparedTask.taskId())
                    .pipelineId(Long.parseLong(preparedTask.pipelineId()))
                    .build();
            saveNodeLogs(task, pipeline, context.getLogs());
            updateTaskFromContext(task, context, preparedTask.operator());
        });
    }

    private void markTaskFailed(Long taskId, String operator, String errorMessage) {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.executeWithoutResult(status -> {
            IngestionTaskDO update = IngestionTaskDO.builder()
                    .id(taskId)
                    .status(IngestionStatus.FAILED.getValue())
                    .chunkCount(0)
                    .errorMessage(errorMessage)
                    .completedAt(new Date())
                    .updatedBy(operator)
                    .build();
            taskMapper.updateById(update);
        });
    }

    private void updateTaskFromContext(IngestionTaskDO task, IngestionContext context, String operator) {
        task.setStatus(context.getStatus() == null ? IngestionStatus.FAILED.getValue() : context.getStatus().getValue());
        task.setChunkCount(context.getChunks() == null ? 0 : context.getChunks().size());
        task.setErrorMessage(context.getError() == null ? null : context.getError().getMessage());
        task.setCompletedAt(new Date());
        task.setUpdatedBy(operator);
        task.setLogsJson(writeJson(buildLogSummary(context.getLogs())));
        task.setMetadataJson(writeJson(buildTaskMetadata(context)));
        taskMapper.updateById(task);
    }

    private void saveNodeLogs(IngestionTaskDO task, PipelineDefinition pipeline, List<NodeLog> logs) {
        if (logs == null || logs.isEmpty()) {
            return;
        }
        Map<String, Integer> nodeOrderMap = buildNodeOrderMap(pipeline);
        for (NodeLog log : logs) {
            String status = resolveNodeStatus(log);
            String outputJson = truncateOutputJson(log.getOutput());
            IngestionTaskNodeDO nodeDO = IngestionTaskNodeDO.builder()
                    .taskId(task.getId())
                    .pipelineId(task.getPipelineId())
                    .nodeId(log.getNodeId())
                    .nodeType(log.getNodeType())
                    .nodeOrder(nodeOrderMap.getOrDefault(log.getNodeId(), 0))
                    .status(status)
                    .durationMs(log.getDurationMs())
                    .message(log.getMessage())
                    .errorMessage(log.getError())
                    .outputJson(outputJson)
                    .build();
            taskNodeMapper.insert(nodeDO);
        }
    }

    private Map<String, Integer> buildNodeOrderMap(PipelineDefinition pipeline) {
        Map<String, Integer> orderMap = new HashMap<>();
        if (pipeline == null || pipeline.getNodes() == null || pipeline.getNodes().isEmpty()) {
            return orderMap;
        }
        Map<String, NodeConfig> nodeMap = new LinkedHashMap<>();
        for (NodeConfig node : pipeline.getNodes()) {
            if (node == null || !StringUtils.hasText(node.getNodeId())) {
                continue;
            }
            nodeMap.putIfAbsent(node.getNodeId(), node);
        }
        if (nodeMap.isEmpty()) {
            return orderMap;
        }
        Set<String> referenced = new HashSet<>();
        for (NodeConfig node : nodeMap.values()) {
            if (StringUtils.hasText(node.getNextNodeId())) {
                referenced.add(node.getNextNodeId());
            }
        }
        int order = 1;
        Set<String> visited = new HashSet<>();
        for (String nodeId : nodeMap.keySet()) {
            if (referenced.contains(nodeId)) {
                continue;
            }
            String current = nodeId;
            while (StringUtils.hasText(current) && !visited.contains(current)) {
                orderMap.put(current, order++);
                visited.add(current);
                NodeConfig config = nodeMap.get(current);
                if (config == null) {
                    break;
                }
                current = config.getNextNodeId();
            }
        }
        for (String nodeId : nodeMap.keySet()) {
            if (!visited.contains(nodeId)) {
                orderMap.put(nodeId, order++);
            }
        }
        return orderMap;
    }

    private String resolveNodeStatus(NodeLog log) {
        if (log == null) {
            return "failed";
        }
        if (!log.isSuccess()) {
            return "failed";
        }
        String message = log.getMessage();
        if (message != null && message.startsWith("Skipped:")) {
            return "skipped";
        }
        return "success";
    }

    private Map<String, Object> buildTaskMetadata(IngestionContext context) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (context.getMetadata() != null) {
            data.putAll(context.getMetadata());
        }
        if (context.getKeywords() != null && !context.getKeywords().isEmpty()) {
            data.put("keywords", context.getKeywords());
        }
        if (context.getQuestions() != null && !context.getQuestions().isEmpty()) {
            data.put("questions", context.getQuestions());
        }
        return data.isEmpty() ? null : data;
    }

    private String resolvePipelineId(String pipelineId) {
        if (StringUtils.hasText(pipelineId)) {
            return pipelineId;
        }
        return pipelineService.getOrCreateVisualDefaultPipelineId();
    }

    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return status;
        }
        try {
            return IngestionStatus.fromValue(status).getValue();
        } catch (IllegalArgumentException ex) {
            return status;
        }
    }

    private DocumentSource toSource(DocumentSourceRequest request) {
        Assert.notNull(request, () -> new ClientException("Document source must not be null"));
        DocumentSource source = DocumentSource.builder()
                .type(request.getType())
                .location(request.getLocation())
                .fileName(request.getFileName())
                .credentials(request.getCredentials() == null ? null : new LinkedHashMap<>(request.getCredentials()))
                .build();
        if (source.getType() == null) {
            throw new ClientException("Document source type must not be null");
        }
        return source;
    }

    private IngestionTaskVO toVO(IngestionTaskDO task) {
        return IngestionTaskVO.builder()
                .id(String.valueOf(task.getId()))
                .pipelineId(String.valueOf(task.getPipelineId()))
                .sourceType(normalizeSourceType(task.getSourceType()))
                .sourceLocation(task.getSourceLocation())
                .sourceFileName(task.getSourceFileName())
                .status(normalizeStatus(task.getStatus()))
                .chunkCount(task.getChunkCount())
                .errorMessage(task.getErrorMessage())
                .logs(readLogs(task.getLogsJson()))
                .metadata(sanitizeMetadataForOutput(readRawMap(task.getMetadataJson())))
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .createdBy(task.getCreatedBy())
                .createTime(task.getCreateTime())
                .updateTime(task.getUpdateTime())
                .build();
    }

    private IngestionTaskNodeVO toNodeVO(IngestionTaskNodeDO node) {
        return IngestionTaskNodeVO.builder()
                .id(String.valueOf(node.getId()))
                .taskId(String.valueOf(node.getTaskId()))
                .pipelineId(String.valueOf(node.getPipelineId()))
                .nodeId(node.getNodeId())
                .nodeType(normalizeNodeType(node.getNodeType()))
                .nodeOrder(node.getNodeOrder())
                .status(normalizeNodeStatus(node.getStatus()))
                .durationMs(node.getDurationMs())
                .message(node.getMessage())
                .errorMessage(node.getErrorMessage())
                .output(readRawMap(node.getOutputJson()))
                .createTime(node.getCreateTime())
                .updateTime(node.getUpdateTime())
                .build();
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private List<NodeLog> buildLogSummary(List<NodeLog> logs) {
        if (logs == null) {
            return List.of();
        }
        return logs.stream()
                .map(log -> NodeLog.builder()
                        .nodeId(log.getNodeId())
                        .nodeType(log.getNodeType())
                        .message(log.getMessage())
                        .durationMs(log.getDurationMs())
                        .success(log.isSuccess())
                        .error(log.getError())
                        .output(null)
                        .build())
                .toList();
    }

    private List<NodeLog> readLogs(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<List<NodeLog>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> readRawMap(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Map.of();
        }
        try {
            Object value = objectMapper.readValue(raw, Object.class);
            if (value instanceof Map<?, ?> mapValue) {
                Map<String, Object> result = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return result;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("value", value);
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> sanitizeMetadataForOutput(Map<String, Object> rawMetadata) {
        if (rawMetadata == null || rawMetadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rawMetadata.entrySet()) {
            if (!isInternalMetadataKey(entry.getKey())) {
                sanitized.put(entry.getKey(), entry.getValue());
            }
        }
        return sanitized;
    }

    private boolean isInternalMetadataKey(String key) {
        return key != null && key.startsWith(INTERNAL_METADATA_PREFIX);
    }

    private String normalizeSourceType(String sourceType) {
        if (!StringUtils.hasText(sourceType)) {
            return sourceType;
        }
        try {
            return SourceType.fromValue(sourceType).getValue();
        } catch (IllegalArgumentException ex) {
            return sourceType;
        }
    }

    private String normalizeNodeType(String nodeType) {
        if (!StringUtils.hasText(nodeType)) {
            return nodeType;
        }
        try {
            return IngestionNodeType.fromValue(nodeType).getValue();
        } catch (IllegalArgumentException ex) {
            return nodeType;
        }
    }

    private String normalizeNodeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return status;
        }
        String trimmed = status.trim();
        String lower = trimmed.toLowerCase();
        return lower.replace('-', '_');
    }

    private String truncateOutputJson(Object output) {
        if (output == null) {
            return null;
        }
        String json = writeJson(output);
        if (json == null) {
            return null;
        }
        int maxSize = 1024 * 1024;
        if (json.length() <= maxSize) {
            return json;
        }
        String truncated = json.substring(0, maxSize - 100);
        return truncated + "... [output truncated, original size=" + json.length() + "]";
    }

    private IngestionResult acceptedResult(PreparedTask preparedTask) {
        return IngestionResult.builder()
                .taskId(String.valueOf(preparedTask.taskId()))
                .pipelineId(preparedTask.pipelineId())
                .status(IngestionStatus.PENDING)
                .chunkCount(0)
                .message(TASK_ACCEPTED_MESSAGE)
                .build();
    }

    private IngestionResult failedResult(PreparedTask preparedTask, String message) {
        return IngestionResult.builder()
                .taskId(String.valueOf(preparedTask.taskId()))
                .pipelineId(preparedTask.pipelineId())
                .status(IngestionStatus.FAILED)
                .chunkCount(0)
                .message(message)
                .build();
    }

    private String resolveOperator() {
        return resolveOperator(UserContext.getUsername(), null);
    }

    private String resolveOperator(String primary, String secondary) {
        if (StringUtils.hasText(primary)) {
            return primary;
        }
        if (StringUtils.hasText(secondary)) {
            return secondary;
        }
        return "system";
    }

    private byte[] copyBytes(byte[] rawBytes) {
        return rawBytes == null ? null : Arrays.copyOf(rawBytes, rawBytes.length);
    }

    private Map<String, Object> copyMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        return new LinkedHashMap<>(metadata);
    }

    private DocumentSource copySource(DocumentSource source) {
        if (source == null) {
            return null;
        }
        return DocumentSource.builder()
                .type(source.getType())
                .location(source.getLocation())
                .fileName(source.getFileName())
                .credentials(source.getCredentials() == null ? null : new LinkedHashMap<>(source.getCredentials()))
                .build();
    }

    private Map<String, Object> buildPersistentMetadata(Map<String, Object> requestMetadata,
                                                        DocumentSource source,
                                                        byte[] rawBytes,
                                                        String mimeType,
                                                        VectorSpaceId vectorSpaceId) {
        Map<String, Object> metadata = copyMetadata(requestMetadata);
        if (source != null && source.getCredentials() != null && !source.getCredentials().isEmpty()) {
            metadata = ensureMutableMetadata(metadata);
            metadata.put(INTERNAL_SOURCE_CREDENTIALS, new LinkedHashMap<>(source.getCredentials()));
        }
        if (vectorSpaceId != null) {
            metadata = ensureMutableMetadata(metadata);
            metadata.put(INTERNAL_VECTOR_SPACE_ID, objectMapper.convertValue(vectorSpaceId, new TypeReference<Map<String, Object>>() {
            }));
        }
        if (rawBytes != null && rawBytes.length > 0) {
            metadata = ensureMutableMetadata(metadata);
            metadata.put(INTERNAL_STAGED_FILE_PATH, stageUploadPayload(rawBytes, source == null ? null : source.getFileName()));
            if (StringUtils.hasText(mimeType)) {
                metadata.put(INTERNAL_MIME_TYPE, mimeType);
            }
        }
        return metadata;
    }

    private Map<String, Object> ensureMutableMetadata(Map<String, Object> metadata) {
        return metadata == null ? new LinkedHashMap<>() : metadata;
    }

    private String stageUploadPayload(byte[] rawBytes, String fileName) {
        try {
            Files.createDirectories(UPLOAD_STAGING_ROOT);
            String safeFileName = sanitizeFileName(fileName);
            Path stagedFile = UPLOAD_STAGING_ROOT.resolve(System.currentTimeMillis()
                    + "-" + UUID.randomUUID()
                    + "-" + safeFileName);
            Files.write(stagedFile, rawBytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return stagedFile.toAbsolutePath().toString();
        } catch (IOException ex) {
            throw new ClientException("Failed to stage uploaded file for background ingestion: " + ex.getMessage());
        }
    }

    private String sanitizeFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "upload.bin";
        }
        return Paths.get(fileName).getFileName().toString().replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private List<IngestionTaskDO> listRecoverableTasks() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        return txTemplate.execute(status -> taskMapper.selectList(new LambdaQueryWrapper<IngestionTaskDO>()
                .eq(IngestionTaskDO::getDeleted, 0)
                .in(IngestionTaskDO::getStatus, IngestionStatus.PENDING.getValue(), IngestionStatus.RUNNING.getValue())
                .orderByAsc(IngestionTaskDO::getCreateTime)));
    }

    private PreparedTask rebuildPreparedTask(IngestionTaskDO task) {
        Map<String, Object> metadata = readRawMap(task.getMetadataJson());
        SourceType sourceType = normalizeSourceType(task.getSourceType()) == null
                ? null
                : SourceType.fromValue(task.getSourceType());
        DocumentSource source = DocumentSource.builder()
                .type(sourceType)
                .location(task.getSourceLocation())
                .fileName(task.getSourceFileName())
                .credentials(readStringMap(metadata.get(INTERNAL_SOURCE_CREDENTIALS)))
                .build();
        byte[] rawBytes = loadStagedBytes(metadata);
        String mimeType = asString(metadata.get(INTERNAL_MIME_TYPE));
        VectorSpaceId vectorSpaceId = readVectorSpaceId(metadata.get(INTERNAL_VECTOR_SPACE_ID));
        String operator = resolveOperator(task.getUpdatedBy(), task.getCreatedBy());
        return new PreparedTask(
                task.getId(),
                String.valueOf(task.getPipelineId()),
                source,
                rawBytes,
                mimeType,
                vectorSpaceId,
                metadata,
                operator
        );
    }

    private byte[] loadStagedBytes(Map<String, Object> metadata) {
        String stagedFilePath = asString(metadata.get(INTERNAL_STAGED_FILE_PATH));
        if (!StringUtils.hasText(stagedFilePath)) {
            return null;
        }
        try {
            Path stagedFile = Paths.get(stagedFilePath);
            if (!Files.exists(stagedFile)) {
                throw new ClientException("staged upload file not found: " + stagedFilePath);
            }
            return Files.readAllBytes(stagedFile);
        } catch (IOException ex) {
            throw new ClientException("failed to load staged upload file: " + ex.getMessage());
        }
    }

    private Map<String, String> readStringMap(Object value) {
        if (!(value instanceof Map<?, ?> rawMap) || rawMap.isEmpty()) {
            return null;
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue() == null ? null : String.valueOf(entry.getValue()));
        }
        return result;
    }

    private VectorSpaceId readVectorSpaceId(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.convertValue(value, VectorSpaceId.class);
        } catch (IllegalArgumentException ex) {
            throw new ClientException("invalid vectorSpaceId metadata: " + ex.getMessage());
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void cleanupStagedFile(Map<String, Object> metadata) {
        String stagedFilePath = asString(metadata == null ? null : metadata.get(INTERNAL_STAGED_FILE_PATH));
        if (!StringUtils.hasText(stagedFilePath)) {
            return;
        }
        try {
            Files.deleteIfExists(Paths.get(stagedFilePath));
        } catch (IOException ex) {
            log.warn("Failed to delete staged upload file {}", stagedFilePath, ex);
        }
    }

    private record PreparedTask(Long taskId,
                                String pipelineId,
                                DocumentSource source,
                                byte[] rawBytes,
                                String mimeType,
                                VectorSpaceId vectorSpaceId,
                                Map<String, Object> metadata,
                                String operator) {
    }
}
