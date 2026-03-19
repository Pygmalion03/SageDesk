package com.nageoffer.ai.ragent.core.chunk;

import com.nageoffer.ai.ragent.infra.embedding.EmbeddingClient;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;

import java.util.List;

public abstract class AbstractEmbeddingChunker implements ChunkingStrategy {
    protected AbstractEmbeddingChunker(ModelSelector modelSelector, List<EmbeddingClient> embeddingClients) {
    }

    @Override
    public final List<VectorChunk> chunk(String text, ChunkingOptions config) {
        return doChunk(text, config);
    }

    protected abstract List<VectorChunk> doChunk(String text, ChunkingOptions config);
}
