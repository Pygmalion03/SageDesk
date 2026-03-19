package com.nageoffer.ai.ragent.core.chunk;

import java.util.HashMap;
import java.util.Map;

public class ChunkingOptions {
    private Integer chunkSize = 512;
    private Integer overlapSize = 128;
    private Map<String, Object> metadata = new HashMap<>();

    public static ChunkingOptions of(int chunkSize, int overlapSize) {
        ChunkingOptions options = new ChunkingOptions();
        options.chunkSize = chunkSize;
        options.overlapSize = overlapSize;
        return options;
    }

    public Integer getChunkSize() {
        return chunkSize;
    }

    public Integer getOverlapSize() {
        return overlapSize;
    }

    @SuppressWarnings("unchecked")
    public <T> T getMetadata(String key, T defaultValue) {
        Object value = metadata.get(key);
        return value != null ? (T) value : defaultValue;
    }
}
