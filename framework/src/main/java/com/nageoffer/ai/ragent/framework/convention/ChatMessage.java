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

package com.nageoffer.ai.ragent.framework.convention;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 对话消息实体
 */
@Data
@NoArgsConstructor
public class ChatMessage {

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT;

        public static Role fromString(String value) {
            for (Role role : Role.values()) {
                if (role.name().equalsIgnoreCase(value)) {
                    return role;
                }
            }
            throw new IllegalArgumentException("无效的角色类型: " + value);
        }
    }

    @Data
    @NoArgsConstructor
    public static class ContentPart {

        public enum Type {
            TEXT,
            IMAGE_URL
        }

        private Type type;

        private String text;

        private String imageUrl;

        private ContentPart(Type type, String text, String imageUrl) {
            this.type = type;
            this.text = text;
            this.imageUrl = imageUrl;
        }

        public static ContentPart text(String text) {
            return new ContentPart(Type.TEXT, text, null);
        }

        public static ContentPart imageUrl(String imageUrl) {
            return new ContentPart(Type.IMAGE_URL, null, imageUrl);
        }

        public boolean isText() {
            return type == Type.TEXT;
        }

        public boolean isImageUrl() {
            return type == Type.IMAGE_URL;
        }
    }

    private Role role;

    private String content;

    private List<ContentPart> parts = new ArrayList<>();

    public ChatMessage(Role role, String content) {
        this.role = role;
        this.content = content;
        this.parts = new ArrayList<>();
    }

    public ChatMessage(Role role, List<ContentPart> parts) {
        this.role = role;
        this.content = null;
        this.parts = parts == null ? new ArrayList<>() : new ArrayList<>(parts);
    }

    public List<ContentPart> getParts() {
        if (parts == null) {
            parts = new ArrayList<>();
        }
        return parts;
    }

    public String getContent() {
        if (content != null && !content.isBlank()) {
            return content;
        }
        return getTextContent();
    }

    public String getTextContent() {
        return getParts().stream()
                .filter(ContentPart::isText)
                .map(ContentPart::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
    }

    public boolean hasParts() {
        return !getParts().isEmpty();
    }

    public boolean hasImageParts() {
        return getParts().stream().anyMatch(ContentPart::isImageUrl);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content);
    }

    public static ChatMessage userParts(List<ContentPart> parts) {
        return new ChatMessage(Role.USER, parts);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content);
    }
}
