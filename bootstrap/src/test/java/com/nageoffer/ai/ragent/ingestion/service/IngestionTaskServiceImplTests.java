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

package com.nageoffer.ai.ragent.ingestion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionTaskCreateRequest;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionTaskVO;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionTaskDO;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionTaskNodeDO;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionTaskMapper;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionTaskNodeMapper;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.context.NodeLog;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionStatus;
import com.nageoffer.ai.ragent.ingestion.domain.enums.SourceType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import com.nageoffer.ai.ragent.ingestion.domain.result.IngestionResult;
import com.nageoffer.ai.ragent.ingestion.engine.IngestionEngine;
import com.nageoffer.ai.ragent.ingestion.service.impl.IngestionTaskServiceImpl;
import com.nageoffer.ai.ragent.rag.controller.request.DocumentSourceRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionTaskServiceImplTests {

    @TempDir
    Path tempDir;

    @Test
    void shouldAcceptTaskAndCompleteItInBackground() {
        IngestionEngine engine = mock(IngestionEngine.class);
        IngestionPipelineService pipelineService = mock(IngestionPipelineService.class);
        IngestionTaskMapper taskMapper = mock(IngestionTaskMapper.class);
        IngestionTaskNodeMapper taskNodeMapper = mock(IngestionTaskNodeMapper.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        doNothing().when(transactionManager).commit(any());
        doNothing().when(transactionManager).rollback(any());

        PipelineDefinition pipeline = PipelineDefinition.builder()
                .id("10001")
                .name("visual-default")
                .nodes(List.of(NodeConfig.builder()
                        .nodeId("parser")
                        .nodeType("parser")
                        .build()))
                .build();
        when(pipelineService.getDefinition("10001")).thenReturn(pipeline);
        when(taskMapper.insert(any(IngestionTaskDO.class))).thenAnswer(invocation -> {
            IngestionTaskDO task = invocation.getArgument(0);
            task.setId(1001L);
            return 1;
        });

        when(engine.execute(eq(pipeline), any(IngestionContext.class))).thenAnswer(invocation -> {
            IngestionContext context = invocation.getArgument(1);
            context.setStatus(IngestionStatus.COMPLETED);
            context.setChunks(List.of(mock(VectorChunk.class), mock(VectorChunk.class)));
            context.setMetadata(Map.of("sourceLabel", "demo"));
            context.setLogs(List.of(NodeLog.builder()
                    .nodeId("parser")
                    .nodeType("parser")
                    .message("parsed")
                    .durationMs(12L)
                    .success(true)
                    .build()));
            return context;
        });

        Executor directExecutor = Runnable::run;
        IngestionTaskServiceImpl service = new IngestionTaskServiceImpl(
                engine,
                pipelineService,
                taskMapper,
                taskNodeMapper,
                new ObjectMapper(),
                transactionManager,
                directExecutor
        );

        IngestionTaskCreateRequest request = new IngestionTaskCreateRequest();
        request.setPipelineId("10001");
        request.setMetadata(Map.of("sourceLabel", "demo"));
        DocumentSourceRequest source = new DocumentSourceRequest();
        source.setType(SourceType.FILE);
        source.setLocation("E:/Projects/ragent/data/samples/product-center-v43.pdf");
        source.setFileName("product-center-v43.pdf");
        request.setSource(source);

        IngestionResult result = service.execute(request);

        Assertions.assertEquals(IngestionStatus.PENDING, result.getStatus());
        Assertions.assertEquals("1001", result.getTaskId());
        Assertions.assertEquals("10001", result.getPipelineId());

        ArgumentCaptor<IngestionTaskDO> insertCaptor = ArgumentCaptor.forClass(IngestionTaskDO.class);
        verify(taskMapper).insert(insertCaptor.capture());
        Assertions.assertEquals(IngestionStatus.PENDING.getValue(), insertCaptor.getValue().getStatus());

        ArgumentCaptor<IngestionTaskDO> updateCaptor = ArgumentCaptor.forClass(IngestionTaskDO.class);
        verify(taskMapper, times(2)).updateById(updateCaptor.capture());
        List<IngestionTaskDO> updates = updateCaptor.getAllValues();
        Assertions.assertEquals(IngestionStatus.RUNNING.getValue(), updates.get(0).getStatus());
        Assertions.assertEquals(IngestionStatus.COMPLETED.getValue(), updates.get(1).getStatus());
        Assertions.assertEquals(2, updates.get(1).getChunkCount());
        Assertions.assertTrue(updates.get(1).getMetadataJson().contains("sourceLabel"));

        verify(taskNodeMapper, times(1)).insert(any(IngestionTaskNodeDO.class));
    }

    @Test
    void shouldReturnFailedResultWhenTaskQueueRejectsSubmission() {
        IngestionEngine engine = mock(IngestionEngine.class);
        IngestionPipelineService pipelineService = mock(IngestionPipelineService.class);
        IngestionTaskMapper taskMapper = mock(IngestionTaskMapper.class);
        IngestionTaskNodeMapper taskNodeMapper = mock(IngestionTaskNodeMapper.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        doNothing().when(transactionManager).commit(any());
        doNothing().when(transactionManager).rollback(any());

        PipelineDefinition pipeline = PipelineDefinition.builder()
                .id("10002")
                .name("visual-default")
                .nodes(List.of())
                .build();
        when(pipelineService.getDefinition("10002")).thenReturn(pipeline);
        when(taskMapper.insert(any(IngestionTaskDO.class))).thenAnswer(invocation -> {
            IngestionTaskDO task = invocation.getArgument(0);
            task.setId(2002L);
            return 1;
        });

        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("queue is full");
        };
        IngestionTaskServiceImpl service = new IngestionTaskServiceImpl(
                engine,
                pipelineService,
                taskMapper,
                taskNodeMapper,
                new ObjectMapper(),
                transactionManager,
                rejectingExecutor
        );

        IngestionTaskCreateRequest request = new IngestionTaskCreateRequest();
        request.setPipelineId("10002");
        DocumentSourceRequest source = new DocumentSourceRequest();
        source.setType(SourceType.FILE);
        source.setLocation("demo.pdf");
        source.setFileName("demo.pdf");
        request.setSource(source);

        IngestionResult result = service.execute(request);

        Assertions.assertEquals(IngestionStatus.FAILED, result.getStatus());
        Assertions.assertEquals("2002", result.getTaskId());
        Assertions.assertTrue(result.getMessage().contains("retry later"));

        ArgumentCaptor<IngestionTaskDO> updateCaptor = ArgumentCaptor.forClass(IngestionTaskDO.class);
        verify(taskMapper).updateById(updateCaptor.capture());
        Assertions.assertEquals(IngestionStatus.FAILED.getValue(), updateCaptor.getValue().getStatus());
        verify(engine, never()).execute(any(), any());
    }

    @Test
    void shouldRecoverInterruptedTaskFromStagedUploadFile() throws Exception {
        IngestionEngine engine = mock(IngestionEngine.class);
        IngestionPipelineService pipelineService = mock(IngestionPipelineService.class);
        IngestionTaskMapper taskMapper = mock(IngestionTaskMapper.class);
        IngestionTaskNodeMapper taskNodeMapper = mock(IngestionTaskNodeMapper.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        ObjectMapper objectMapper = new ObjectMapper();

        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        doNothing().when(transactionManager).commit(any());
        doNothing().when(transactionManager).rollback(any());

        PipelineDefinition pipeline = PipelineDefinition.builder()
                .id("30003")
                .name("visual-default")
                .nodes(List.of(NodeConfig.builder()
                        .nodeId("parser")
                        .nodeType("parser")
                        .build()))
                .build();
        when(pipelineService.getDefinition("30003")).thenReturn(pipeline);

        Path stagedFile = tempDir.resolve("upload-stage.pdf");
        Files.write(stagedFile, "stage-data".getBytes());
        Map<String, Object> metadata = Map.of(
                "__ingestion_internal__stagedFilePath", stagedFile.toString(),
                "__ingestion_internal__mimeType", "application/pdf",
                "__ingestion_internal__vectorSpaceId", Map.of("logicalName", "kb_demo", "namespace", "default"),
                "sourceLabel", "demo"
        );
        IngestionTaskDO task = IngestionTaskDO.builder()
                .id(3003L)
                .pipelineId(30003L)
                .sourceType(SourceType.FILE.getValue())
                .sourceLocation("upload://product-center-v43.pdf")
                .sourceFileName("product-center-v43.pdf")
                .status(IngestionStatus.RUNNING.getValue())
                .createdBy("admin")
                .updatedBy("admin")
                .metadataJson(objectMapper.writeValueAsString(metadata))
                .build();
        when(taskMapper.selectList(any())).thenReturn(List.of(task));

        when(engine.execute(eq(pipeline), any(IngestionContext.class))).thenAnswer(invocation -> {
            IngestionContext context = invocation.getArgument(1);
            Assertions.assertArrayEquals("stage-data".getBytes(), context.getRawBytes());
            Assertions.assertEquals("application/pdf", context.getMimeType());
            Assertions.assertEquals("kb_demo", context.getVectorSpaceId().getLogicalName());
            context.setStatus(IngestionStatus.COMPLETED);
            context.setChunks(List.of(mock(VectorChunk.class)));
            context.setMetadata(Map.of(
                    "__ingestion_internal__stagedFilePath", stagedFile.toString(),
                    "__ingestion_internal__mimeType", "application/pdf",
                    "sourceLabel", "demo"
            ));
            return context;
        });

        IngestionTaskServiceImpl service = new IngestionTaskServiceImpl(
                engine,
                pipelineService,
                taskMapper,
                taskNodeMapper,
                objectMapper,
                transactionManager,
                Runnable::run
        );

        service.recoverInterruptedTasks();

        verify(taskMapper, times(2)).updateById(any(IngestionTaskDO.class));
        verify(taskNodeMapper).delete(any());
        Assertions.assertFalse(Files.exists(stagedFile));
    }

    @Test
    void shouldHideInternalMetadataFromTaskView() throws Exception {
        IngestionEngine engine = mock(IngestionEngine.class);
        IngestionPipelineService pipelineService = mock(IngestionPipelineService.class);
        IngestionTaskMapper taskMapper = mock(IngestionTaskMapper.class);
        IngestionTaskNodeMapper taskNodeMapper = mock(IngestionTaskNodeMapper.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        ObjectMapper objectMapper = new ObjectMapper();

        IngestionTaskDO task = IngestionTaskDO.builder()
                .id(4004L)
                .pipelineId(40004L)
                .status(IngestionStatus.COMPLETED.getValue())
                .metadataJson(objectMapper.writeValueAsString(Map.of(
                        "__ingestion_internal__stagedFilePath", "C:/temp/a.pdf",
                        "sourceLabel", "demo"
                )))
                .build();
        when(taskMapper.selectById("4004")).thenReturn(task);

        IngestionTaskServiceImpl service = new IngestionTaskServiceImpl(
                engine,
                pipelineService,
                taskMapper,
                taskNodeMapper,
                objectMapper,
                transactionManager,
                Runnable::run
        );

        IngestionTaskVO vo = service.get("4004");

        Assertions.assertEquals(Map.of("sourceLabel", "demo"), vo.getMetadata());
    }
}
