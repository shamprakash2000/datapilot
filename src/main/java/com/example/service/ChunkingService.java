package com.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class ChunkingService {

    private static final Logger log = LoggerFactory.getLogger(ChunkingService.class);

    // Max words per chunk — balances context richness vs embedding precision
    private static final int CHUNK_SIZE = 100;

    // Words shared between consecutive chunks — prevents losing meaning at chunk boundaries
    private static final int CHUNK_OVERLAP = 20;

    /**
     * Splits text into overlapping chunks at sentence boundaries.
     * Accumulates sentences until chunk size is reached, then starts a new chunk
     * with the last CHUNK_OVERLAP words carried over for context continuity.
     */
    public List<String> chunk(String text) {
        // Split into sentences — never cut mid-sentence
        String[] sentences = text.split("(?<=[.!?])\\s+");

        List<String> chunks = new ArrayList<>();
        List<String> currentWords = new ArrayList<>();

        for (String sentence : sentences) {
            String[] sentenceWords = sentence.trim().split("\\s+");

            // If adding this sentence would exceed chunk size, save current chunk first
            if (currentWords.size() + sentenceWords.length > CHUNK_SIZE && !currentWords.isEmpty()) {
                chunks.add(String.join(" ", currentWords));

                // Carry over last CHUNK_OVERLAP words into the next chunk for context continuity
                int overlapStart = Math.max(0, currentWords.size() - CHUNK_OVERLAP);
                currentWords = new ArrayList<>(currentWords.subList(overlapStart, currentWords.size()));
            }

            currentWords.addAll(Arrays.asList(sentenceWords));
        }

        // Add whatever remains as the final chunk
        if (!currentWords.isEmpty()) {
            chunks.add(String.join(" ", currentWords));
        }

        log.info("Split document into {} chunks (chunkSize={}, overlap={})",
                chunks.size(), CHUNK_SIZE, CHUNK_OVERLAP);
        return chunks;
    }
}
