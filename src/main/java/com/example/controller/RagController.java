package com.example.controller;

import com.example.config.Prompts;
import com.example.model.ChunkData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import com.example.service.ChunkingService;
import com.example.service.EmbeddingService;
import com.example.service.PineconeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Tag(name = "Plain HTTP RAG", description = "RAG pipeline using direct HTTP calls to Gemini and Pinecone. Vectors stored in Pinecone default namespace.")
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.api.url}")
    private String apiUrl;

    private final EmbeddingService embeddingService;
    private final PineconeService pineconeService;
    private final ChunkingService chunkingService;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RagController(EmbeddingService embeddingService, PineconeService pineconeService,
                         ChunkingService chunkingService) {
        this.embeddingService = embeddingService;
        this.pineconeService = pineconeService;
        this.chunkingService = chunkingService;
    }

    /**
     * Store a document — splits into chunks, batch embeds, stores each chunk in Pinecone.
     * POST /api/rag/ingest
     * Body: {"text": "your full document text here..."}
     */
    private static final int MAX_INGEST_CHARS = 50_000;

    @Operation(summary = "Ingest a document", description = "Chunks the text, batch-embeds all chunks in one Gemini call, batch-upserts to Pinecone. Deterministic IDs — re-ingesting the same document overwrites existing vectors. Max 50,000 characters.")
    @RequestBody(required = true, content = @Content(examples = @ExampleObject(value = "{\"text\": \"Your document content here...\"}")))
    @PostMapping("/ingest")
    public Map<String, String> ingest(@org.springframework.web.bind.annotation.RequestBody Map<String, String> request) throws Exception {
        String text = request.get("text");
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text is required");
        }
        if (text.length() > MAX_INGEST_CHARS) {
            throw new IllegalArgumentException("text exceeds maximum allowed size of " + MAX_INGEST_CHARS + " characters");
        }

        // SHA-256 of the full document — links all chunks back to the same source document
        String documentId = sha256(text);

        // Split into overlapping sentence-aware chunks
        List<String> chunks = chunkingService.chunk(text);
        int totalChunks = chunks.size();

        // One API call to embed all chunks — avoids N sequential round trips to Gemini
        List<List<Float>> vectors = embeddingService.batchEmbed(chunks);

        // Build ChunkData list pairing each chunk text with its vector and metadata
        List<ChunkData> chunkDataList = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            // Chunk ID = documentId + index — deterministic, so re-ingesting same doc overwrites
            String chunkId = documentId + "-chunk-" + i;
            chunkDataList.add(new ChunkData(chunkId, vectors.get(i), chunks.get(i), documentId, i, totalChunks));
        }

        // One API call to store all chunks — avoids N sequential round trips to Pinecone
        pineconeService.batchUpsert(chunkDataList);

        log.info("Ingested document {} as {} chunks", documentId, totalChunks);
        return Map.of(
                "status", "stored",
                "documentId", documentId,
                "chunks", String.valueOf(totalChunks)
        );
    }

    /**
     * Ask a question using RAG.
     * POST /api/rag/ask
     * Body: {"question": "What is Spring Boot?"}
     *
     * What happens:
     * 1. Embed the question (single embed — only one text)
     * 2. Search Pinecone for most similar chunks
     * 3. Send relevant chunks + question to Gemini
     * 4. Gemini answers using only your ingested content
     */
    @Operation(summary = "Ask a question", description = "Embeds the question, searches Pinecone (top 3, threshold 0.70), builds context, and asks Gemini to answer using only that context.")
    @RequestBody(required = true, content = @Content(examples = @ExampleObject(value = "{\"question\": \"What is RAG?\"}")))
    @PostMapping("/ask")
    public Map<String, String> ask(@org.springframework.web.bind.annotation.RequestBody Map<String, String> request) throws Exception {
        String question = request.get("question");

        // Single embed — ask flow always has exactly one text (the question)
        List<Float> questionVector = embeddingService.embed(question);

        List<String> relevantDocs = pineconeService.search(questionVector, 3);
        log.info("Found {} relevant chunks above score threshold", relevantDocs.size());

        if (relevantDocs.isEmpty()) {
            return Map.of("question", question, "answer", "No relevant documents found. Please ingest some documents first.");
        }

        log.info("Relevant chunks : {}", relevantDocs);

        String context = String.join("\n\n", relevantDocs);
        String prompt = Prompts.PLAIN_RAG_PROMPT_TEMPLATE.formatted(context, question);

        String requestBody = """
                {
                  "contents": [{"parts": [{"text": "%s"}]}]
                }
                """.formatted(prompt.replace("\"", "\\\"").replace("\n", "\\n"));

        log.info("Sending prompt to Gemini with {} context chunks", relevantDocs.size());

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .header("X-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        log.info("Gemini response: {}", httpResponse.body());

        JsonNode root = objectMapper.readTree(httpResponse.body());
        String answer = root.path("candidates").get(0)
                .path("content").path("parts").get(0)
                .path("text").asText();

        return Map.of(
                "question", question,
                "answer", answer,
                "chunksUsed", String.valueOf(relevantDocs.size())
        );
    }

    private String sha256(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
