package com.nageoffer.ai.ragent.core.chunk;

import java.util.List;

public interface ChunkingStrategy {
    ChunkingMode getType();
    List<VectorChunk> chunk(String text, ChunkingOptions config);
}
