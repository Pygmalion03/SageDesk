package com.nageoffer.ai.ragent.core.chunk;

import java.util.HashMap;
import java.util.Map;

public class VectorChunk {
    private String chunkId;
    private Integer index;
    private String content;
    private Map<String, Object> metadata = new HashMap<>();
    private float[] embedding;

    public static Builder builder() {
        return new Builder();
    }

    public String getChunkId() { return chunkId; }
    public Integer getIndex() { return index; }
    public String getContent() { return content; }
    public Map<String, Object> getMetadata() { return metadata; }
    public float[] getEmbedding() { return embedding; }
    public void setEmbedding(float[] embedding) { this.embedding = embedding; }

    public static class Builder {
        private final VectorChunk chunk = new VectorChunk();
        public Builder chunkId(String chunkId) { chunk.chunkId = chunkId; return this; }
        public Builder index(Integer index) { chunk.index = index; return this; }
        public Builder content(String content) { chunk.content = content; return this; }
        public Builder metadata(Map<String, Object> metadata) { chunk.metadata = metadata; return this; }
        public Builder embedding(float[] embedding) { chunk.embedding = embedding; return this; }
        public VectorChunk build() { return chunk; }
    }
}
