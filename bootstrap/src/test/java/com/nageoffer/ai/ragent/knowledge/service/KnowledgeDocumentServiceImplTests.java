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

package com.nageoffer.ai.ragent.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.ChunkingMode;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.core.chunk.ChunkingStrategy;
import com.nageoffer.ai.ragent.core.chunk.ChunkingStrategyFactory;
import com.nageoffer.ai.ragent.core.parser.DocumentParser;
import com.nageoffer.ai.ragent.core.parser.DocumentParserSelector;
import com.nageoffer.ai.ragent.core.parser.ParserType;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionPipelineMapper;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.SourceType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import com.nageoffer.ai.ragent.ingestion.engine.IngestionEngine;
import com.nageoffer.ai.ragent.ingestion.service.IngestionPipelineService;
import com.nageoffer.ai.ragent.ingestion.util.HttpClientHelper;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentChunkLogMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeDocumentServiceImpl;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeDocumentServiceImplTests {

    @Test
    void pipelineProcessShouldPreserveDocumentSourceForTypeDetection() throws Exception {
        KnowledgeBaseMapper kbMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeDocumentMapper docMapper = mock(KnowledgeDocumentMapper.class);
        DocumentParserSelector parserSelector = mock(DocumentParserSelector.class);
        ChunkingStrategyFactory chunkingStrategyFactory = mock(ChunkingStrategyFactory.class);
        FileStorageService fileStorageService = mock(FileStorageService.class);
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        KnowledgeChunkService knowledgeChunkService = mock(KnowledgeChunkService.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        HttpClientHelper httpClientHelper = mock(HttpClientHelper.class);
        KnowledgeDocumentScheduleService scheduleService = mock(KnowledgeDocumentScheduleService.class);
        IngestionPipelineService ingestionPipelineService = mock(IngestionPipelineService.class);
        IngestionPipelineMapper ingestionPipelineMapper = mock(IngestionPipelineMapper.class);
        IngestionEngine ingestionEngine = mock(IngestionEngine.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        KnowledgeDocumentChunkLogMapper chunkLogMapper = mock(KnowledgeDocumentChunkLogMapper.class);
        Executor executor = Runnable::run;
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

        KnowledgeDocumentServiceImpl service = new KnowledgeDocumentServiceImpl(
                kbMapper,
                docMapper,
                parserSelector,
                chunkingStrategyFactory,
                fileStorageService,
                vectorStoreService,
                knowledgeChunkService,
                embeddingService,
                httpClientHelper,
                new ObjectMapper(),
                scheduleService,
                ingestionPipelineService,
                ingestionPipelineMapper,
                ingestionEngine,
                redissonClient,
                chunkLogMapper,
                executor,
                transactionManager
        );

        KnowledgeBaseDO kb = KnowledgeBaseDO.builder()
                .id(10L)
                .collectionName("kb_collection")
                .build();
        when(kbMapper.selectById(10L)).thenReturn(kb);
        when(fileStorageService.openStream("s3://kb/photo.jpg"))
                .thenReturn(new ByteArrayInputStream(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9}));

        PipelineDefinition pipeline = PipelineDefinition.builder()
                .id("100")
                .name("visual-default")
                .nodes(List.of())
                .build();
        when(ingestionPipelineService.getDefinition("100")).thenReturn(pipeline);
        when(ingestionEngine.execute(eq(pipeline), any(IngestionContext.class))).thenAnswer(invocation -> {
            IngestionContext context = invocation.getArgument(1);
            context.setChunks(List.of(VectorChunk.builder()
                    .chunkId("chunk-1")
                    .index(0)
                    .content("visual summary")
                    .build()));
            return context;
        });

        KnowledgeDocumentDO document = KnowledgeDocumentDO.builder()
                .id(200L)
                .kbId(10L)
                .docName("photo.jpg")
                .fileType("jpg")
                .fileUrl("s3://kb/photo.jpg")
                .sourceType("file")
                .pipelineId(100L)
                .build();

        Method method = KnowledgeDocumentServiceImpl.class.getDeclaredMethod("runPipelineProcess", KnowledgeDocumentDO.class);
        method.setAccessible(true);
        method.invoke(service, document);

        ArgumentCaptor<IngestionContext> contextCaptor = ArgumentCaptor.forClass(IngestionContext.class);
        verify(ingestionEngine).execute(eq(pipeline), contextCaptor.capture());
        IngestionContext context = contextCaptor.getValue();

        Assertions.assertNotNull(context.getSource());
        Assertions.assertEquals(SourceType.FILE, context.getSource().getType());
        Assertions.assertEquals("photo.jpg", context.getSource().getFileName());
        Assertions.assertEquals("s3://kb/photo.jpg", context.getSource().getLocation());
        Assertions.assertTrue(context.getMimeType().startsWith("image/"));
    }

    @Test
    void pipelineProcessShouldExposePipelineErrorMessage() throws Exception {
        KnowledgeBaseMapper kbMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeDocumentMapper docMapper = mock(KnowledgeDocumentMapper.class);
        DocumentParserSelector parserSelector = mock(DocumentParserSelector.class);
        ChunkingStrategyFactory chunkingStrategyFactory = mock(ChunkingStrategyFactory.class);
        FileStorageService fileStorageService = mock(FileStorageService.class);
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        KnowledgeChunkService knowledgeChunkService = mock(KnowledgeChunkService.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        HttpClientHelper httpClientHelper = mock(HttpClientHelper.class);
        KnowledgeDocumentScheduleService scheduleService = mock(KnowledgeDocumentScheduleService.class);
        IngestionPipelineService ingestionPipelineService = mock(IngestionPipelineService.class);
        IngestionPipelineMapper ingestionPipelineMapper = mock(IngestionPipelineMapper.class);
        IngestionEngine ingestionEngine = mock(IngestionEngine.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        KnowledgeDocumentChunkLogMapper chunkLogMapper = mock(KnowledgeDocumentChunkLogMapper.class);
        Executor executor = Runnable::run;
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

        KnowledgeDocumentServiceImpl service = new KnowledgeDocumentServiceImpl(
                kbMapper,
                docMapper,
                parserSelector,
                chunkingStrategyFactory,
                fileStorageService,
                vectorStoreService,
                knowledgeChunkService,
                embeddingService,
                httpClientHelper,
                new ObjectMapper(),
                scheduleService,
                ingestionPipelineService,
                ingestionPipelineMapper,
                ingestionEngine,
                redissonClient,
                chunkLogMapper,
                executor,
                transactionManager
        );

        KnowledgeBaseDO kb = KnowledgeBaseDO.builder()
                .id(10L)
                .collectionName("kb_collection")
                .build();
        when(kbMapper.selectById(10L)).thenReturn(kb);
        when(fileStorageService.openStream("s3://kb/photo.jpg"))
                .thenReturn(new ByteArrayInputStream(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9}));

        PipelineDefinition pipeline = PipelineDefinition.builder()
                .id("100")
                .name("visual-default")
                .nodes(List.of())
                .build();
        when(ingestionPipelineService.getDefinition("100")).thenReturn(pipeline);
        when(ingestionEngine.execute(eq(pipeline), any(IngestionContext.class))).thenAnswer(invocation -> {
            IngestionContext context = invocation.getArgument(1);
            context.setError(new IllegalStateException("Paddle document analysis is disabled"));
            return context;
        });

        KnowledgeDocumentDO document = KnowledgeDocumentDO.builder()
                .id(200L)
                .kbId(10L)
                .docName("photo.jpg")
                .fileType("jpg")
                .fileUrl("s3://kb/photo.jpg")
                .sourceType("file")
                .pipelineId(100L)
                .build();

        Method method = KnowledgeDocumentServiceImpl.class.getDeclaredMethod("runPipelineProcess", KnowledgeDocumentDO.class);
        method.setAccessible(true);

        InvocationTargetException ex = Assertions.assertThrows(InvocationTargetException.class,
                () -> method.invoke(service, document));

        Assertions.assertTrue(ex.getCause().getMessage().contains("Paddle document analysis is disabled"));
    }

    @Test
    void chunkProcessShouldExposeChunkingErrorMessage() throws Exception {
        KnowledgeBaseMapper kbMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeDocumentMapper docMapper = mock(KnowledgeDocumentMapper.class);
        DocumentParserSelector parserSelector = mock(DocumentParserSelector.class);
        ChunkingStrategyFactory chunkingStrategyFactory = mock(ChunkingStrategyFactory.class);
        FileStorageService fileStorageService = mock(FileStorageService.class);
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        KnowledgeChunkService knowledgeChunkService = mock(KnowledgeChunkService.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        HttpClientHelper httpClientHelper = mock(HttpClientHelper.class);
        KnowledgeDocumentScheduleService scheduleService = mock(KnowledgeDocumentScheduleService.class);
        IngestionPipelineService ingestionPipelineService = mock(IngestionPipelineService.class);
        IngestionPipelineMapper ingestionPipelineMapper = mock(IngestionPipelineMapper.class);
        IngestionEngine ingestionEngine = mock(IngestionEngine.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        KnowledgeDocumentChunkLogMapper chunkLogMapper = mock(KnowledgeDocumentChunkLogMapper.class);
        Executor executor = Runnable::run;
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

        KnowledgeDocumentServiceImpl service = new KnowledgeDocumentServiceImpl(
                kbMapper,
                docMapper,
                parserSelector,
                chunkingStrategyFactory,
                fileStorageService,
                vectorStoreService,
                knowledgeChunkService,
                embeddingService,
                httpClientHelper,
                new ObjectMapper(),
                scheduleService,
                ingestionPipelineService,
                ingestionPipelineMapper,
                ingestionEngine,
                redissonClient,
                chunkLogMapper,
                executor,
                transactionManager
        );

        when(kbMapper.selectById(10L)).thenReturn(KnowledgeBaseDO.builder()
                .id(10L)
                .embeddingModel("qwen3-embedding:8b-fp16")
                .build());
        when(fileStorageService.openStream("s3://kb/broken.md"))
                .thenReturn(new ByteArrayInputStream("# Broken".getBytes()));
        DocumentParser parser = mock(DocumentParser.class);
        when(parserSelector.select(ParserType.TIKA.getType())).thenReturn(parser);
        when(parser.extractText(any(), eq("broken.md"))).thenReturn("# Broken");
        ChunkingStrategy strategy = mock(ChunkingStrategy.class);
        when(chunkingStrategyFactory.requireStrategy(ChunkingMode.FIXED_SIZE)).thenReturn(strategy);
        when(strategy.chunk(anyString(), any()))
                .thenThrow(new IllegalStateException("Embedding model not matched: qwen3-embedding:8b-fp16"));

        KnowledgeDocumentDO document = KnowledgeDocumentDO.builder()
                .id(200L)
                .kbId(10L)
                .docName("broken.md")
                .fileUrl("s3://kb/broken.md")
                .chunkStrategy("fixed_size")
                .build();

        Method method = KnowledgeDocumentServiceImpl.class.getDeclaredMethod("runChunkProcess", KnowledgeDocumentDO.class);
        method.setAccessible(true);
        Object result = method.invoke(service, document);

        Method chunksMethod = result.getClass().getDeclaredMethod("getChunks");
        chunksMethod.setAccessible(true);
        Assertions.assertNull(chunksMethod.invoke(result));

        Method errorMethod = result.getClass().getDeclaredMethod("getErrorMessage");
        errorMethod.setAccessible(true);
        String errorMessage = (String) errorMethod.invoke(result);
        Assertions.assertTrue(errorMessage.contains("Embedding model not matched: qwen3-embedding:8b-fp16"));
    }
}
