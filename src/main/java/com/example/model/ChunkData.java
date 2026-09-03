package com.example.model;

import java.util.List;

// Carries all data for one chunk — passed between ingest pipeline stages.
// Using a record keeps it immutable and eliminates boilerplate getters/setters.
public record ChunkData(
        String id,
        List<Float> vector,
        String text,
        String documentId,
        int chunkIndex,
        int totalChunks
) {}
