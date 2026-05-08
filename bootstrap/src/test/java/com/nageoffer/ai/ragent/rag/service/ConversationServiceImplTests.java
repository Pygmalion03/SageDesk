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

package com.nageoffer.ai.ragent.rag.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.controller.request.ConversationCreateRequest;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationSummaryMapper;
import com.nageoffer.ai.ragent.rag.service.impl.ConversationServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayDeque;
import java.util.Date;
import java.util.Queue;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationServiceImplTests {

    @Test
    void shouldCreateConversationWithCheapTitleAndGenerateTitleAsync() {
        ConversationMapper conversationMapper = mock(ConversationMapper.class);
        MemoryProperties memoryProperties = new MemoryProperties();
        memoryProperties.setTitleMaxLength(30);
        PromptTemplateLoader promptTemplateLoader = mock(PromptTemplateLoader.class);
        LLMService llmService = mock(LLMService.class);
        RecordingExecutor executor = new RecordingExecutor();
        ConversationDO[] inserted = new ConversationDO[1];

        when(conversationMapper.selectOne(any(Wrapper.class)))
                .thenReturn(null)
                .thenAnswer(invocation -> inserted[0]);
        when(conversationMapper.insert(any(ConversationDO.class))).thenAnswer(invocation -> {
            inserted[0] = invocation.getArgument(0);
            inserted[0].setId(1L);
            return 1;
        });
        when(promptTemplateLoader.render(any(), any())).thenReturn("title prompt");
        when(llmService.chat(any(ChatRequest.class))).thenReturn("Generated Title");

        ConversationServiceImpl service = newService(
                conversationMapper,
                memoryProperties,
                promptTemplateLoader,
                llmService,
                executor
        );

        service.createOrUpdate(ConversationCreateRequest.builder()
                .conversationId("c1")
                .userId("u1")
                .question("YD-338CC 系列机型相关表格和图像关于越帮 YD-338CC 系列碎纸机")
                .lastTime(new Date())
                .build());

        Assertions.assertEquals("YD-338CC 系列机型相关表格和图像关于越帮 YD-33", inserted[0].getTitle());
        Assertions.assertEquals(1, executor.size());
        verify(llmService, never()).chat(any(ChatRequest.class));

        executor.runNext();

        ArgumentCaptor<ConversationDO> updateCaptor = ArgumentCaptor.forClass(ConversationDO.class);
        verify(conversationMapper).updateById(updateCaptor.capture());
        Assertions.assertEquals("Generated Title", updateCaptor.getValue().getTitle());
    }

    @Test
    void shouldNotGenerateAsyncTitleWhenUserRenamedBeforeTaskRuns() {
        ConversationMapper conversationMapper = mock(ConversationMapper.class);
        MemoryProperties memoryProperties = new MemoryProperties();
        memoryProperties.setTitleMaxLength(30);
        PromptTemplateLoader promptTemplateLoader = mock(PromptTemplateLoader.class);
        LLMService llmService = mock(LLMService.class);
        RecordingExecutor executor = new RecordingExecutor();
        ConversationDO[] inserted = new ConversationDO[1];

        when(conversationMapper.selectOne(any(Wrapper.class)))
                .thenReturn(null)
                .thenAnswer(invocation -> inserted[0]);
        when(conversationMapper.insert(any(ConversationDO.class))).thenAnswer(invocation -> {
            inserted[0] = invocation.getArgument(0);
            inserted[0].setId(2L);
            return 1;
        });

        ConversationServiceImpl service = newService(
                conversationMapper,
                memoryProperties,
                promptTemplateLoader,
                llmService,
                executor
        );

        service.createOrUpdate(ConversationCreateRequest.builder()
                .conversationId("c2")
                .userId("u1")
                .question("帮我介绍 YD-338CC")
                .lastTime(new Date())
                .build());
        inserted[0].setTitle("我手动改过的标题");

        executor.runNext();

        verify(llmService, never()).chat(any(ChatRequest.class));
        verify(conversationMapper, never()).updateById(any(ConversationDO.class));
    }

    @Test
    void shouldNotGenerateAsyncTitleWhenConversationDisappearsBeforeTaskRuns() {
        ConversationMapper conversationMapper = mock(ConversationMapper.class);
        MemoryProperties memoryProperties = new MemoryProperties();
        memoryProperties.setTitleMaxLength(30);
        PromptTemplateLoader promptTemplateLoader = mock(PromptTemplateLoader.class);
        LLMService llmService = mock(LLMService.class);
        RecordingExecutor executor = new RecordingExecutor();

        when(conversationMapper.selectOne(any(Wrapper.class)))
                .thenReturn(null)
                .thenReturn(null);
        when(conversationMapper.insert(any(ConversationDO.class))).thenReturn(1);

        ConversationServiceImpl service = newService(
                conversationMapper,
                memoryProperties,
                promptTemplateLoader,
                llmService,
                executor
        );

        service.createOrUpdate(ConversationCreateRequest.builder()
                .conversationId("c3")
                .userId("u1")
                .question("帮我介绍 YD-338CC")
                .lastTime(new Date())
                .build());

        executor.runNext();

        verify(llmService, never()).chat(any(ChatRequest.class));
        verify(conversationMapper, never()).updateById(any(ConversationDO.class));
    }

    private ConversationServiceImpl newService(ConversationMapper conversationMapper,
                                               MemoryProperties memoryProperties,
                                               PromptTemplateLoader promptTemplateLoader,
                                               LLMService llmService,
                                               Executor executor) {
        ConversationServiceImpl service = new ConversationServiceImpl(
                conversationMapper,
                mock(ConversationMessageMapper.class),
                mock(ConversationSummaryMapper.class),
                memoryProperties,
                promptTemplateLoader,
                llmService
        );
        try {
            ReflectionTestUtils.setField(service, "conversationTitleExecutor", executor);
        } catch (IllegalArgumentException ignored) {
            // The red test runs before the async executor field exists.
        }
        return service;
    }

    private static final class RecordingExecutor implements Executor {

        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        int size() {
            return tasks.size();
        }

        void runNext() {
            Runnable task = tasks.poll();
            Assertions.assertNotNull(task);
            task.run();
        }
    }
}
