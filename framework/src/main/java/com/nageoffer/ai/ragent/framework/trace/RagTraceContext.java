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

package com.nageoffer.ai.ragent.framework.trace;

import com.alibaba.ttl.TransmittableThreadLocal;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RAG Trace 上下文
 * 使用 TTL 在异步线程池中透传 traceId 与节点栈
 */
public final class RagTraceContext {

    private static final TransmittableThreadLocal<String> TRACE_ID = new TransmittableThreadLocal<>();
    private static final TransmittableThreadLocal<String> TASK_ID = new TransmittableThreadLocal<>();
    private static final TransmittableThreadLocal<Deque<String>> NODE_STACK = new TransmittableThreadLocal<>();
    private static final TransmittableThreadLocal<Deque<Map<String, Object>>> NODE_EXTRA_STACK = new TransmittableThreadLocal<>();

    private RagTraceContext() {
    }

    public static String getTraceId() {
        return TRACE_ID.get();
    }

    public static void setTraceId(String traceId) {
        TRACE_ID.set(traceId);
    }

    public static String getTaskId() {
        return TASK_ID.get();
    }

    public static void setTaskId(String taskId) {
        TASK_ID.set(taskId);
    }

    public static int depth() {
        Deque<String> stack = NODE_STACK.get();
        return stack == null ? 0 : stack.size();
    }

    public static String currentNodeId() {
        Deque<String> stack = NODE_STACK.get();
        return stack == null ? null : stack.peek();
    }

    public static void pushNode(String nodeId) {
        Deque<String> nodeStack = NODE_STACK.get();
        if (nodeStack == null) {
            nodeStack = new ArrayDeque<>();
            NODE_STACK.set(nodeStack);
        }
        nodeStack.push(nodeId);

        Deque<Map<String, Object>> extraStack = NODE_EXTRA_STACK.get();
        if (extraStack == null) {
            extraStack = new ArrayDeque<>();
            NODE_EXTRA_STACK.set(extraStack);
        }
        extraStack.push(new LinkedHashMap<>());
    }

    public static void popNode() {
        Deque<String> nodeStack = NODE_STACK.get();
        if (nodeStack == null || nodeStack.isEmpty()) {
            return;
        }
        nodeStack.pop();
        if (nodeStack.isEmpty()) {
            NODE_STACK.remove();
        }

        Deque<Map<String, Object>> extraStack = NODE_EXTRA_STACK.get();
        if (extraStack == null || extraStack.isEmpty()) {
            return;
        }
        extraStack.pop();
        if (extraStack.isEmpty()) {
            NODE_EXTRA_STACK.remove();
        }
    }

    public static void putNodeExtra(String key, Object value) {
        if (key == null || key.isBlank()) {
            return;
        }
        Deque<Map<String, Object>> extraStack = NODE_EXTRA_STACK.get();
        if (extraStack == null || extraStack.isEmpty()) {
            return;
        }
        extraStack.peek().put(key, value);
    }

    public static void putAllNodeExtra(Map<String, Object> extraData) {
        if (extraData == null || extraData.isEmpty()) {
            return;
        }
        Deque<Map<String, Object>> extraStack = NODE_EXTRA_STACK.get();
        if (extraStack == null || extraStack.isEmpty()) {
            return;
        }
        extraStack.peek().putAll(extraData);
    }

    public static Map<String, Object> currentNodeExtraData() {
        Deque<Map<String, Object>> extraStack = NODE_EXTRA_STACK.get();
        if (extraStack == null || extraStack.isEmpty()) {
            return Map.of();
        }
        return new LinkedHashMap<>(extraStack.peek());
    }

    public static void clear() {
        TRACE_ID.remove();
        TASK_ID.remove();
        NODE_STACK.remove();
        NODE_EXTRA_STACK.remove();
    }
}
