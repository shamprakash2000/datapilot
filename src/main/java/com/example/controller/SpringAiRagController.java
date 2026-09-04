package com.example.controller;

import com.example.service.ChunkingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.config.Prompts;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Tag(name = "Spring AI RAG", description = "RAG pipeline using Spring AI VectorStore and ChatClient. Vectors stored in Pinecone namespace 'spring-ai'. Falls back to direct LLM when no context found.")
@RestController
@RequestMapping("/api/rag-ai")
public class SpringAiRagController {

    private static final Logger log = LoggerFactory.getLogger(SpringAiRagController.class);

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final ChunkingService chunkingService;

    public SpringAiRagController(VectorStore vectorStore, ChatClient.Builder chatClientBuilder,
                                  ChunkingService chunkingService) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClientBuilder.build();
        this.chunkingService = chunkingService;
    }

    /**
     * POST /api/rag-ai/ingest
     * Body: {"text": "...document content..."}
     *
     * Chunks the text, then calls vectorStore.add() which internally:
     *   1. Embeds each chunk via EmbeddingModel (Gemini gemini-embedding-001)
     *   2. Upserts all vectors to Pinecone in one call
     */
    private static final int MAX_INGEST_CHARS = 50_000;

    @Operation(summary = "Ingest a document", description = "Chunks the text, embeds each chunk via Gemini, and upserts all vectors to Pinecone. Max 50,000 characters.")
    @RequestBody(required = true, content = @Content(examples = @ExampleObject(value = "{\"text\": \"Spring AI is a framework that simplifies AI integration in Spring Boot applications...\"}")))
    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingest(@org.springframework.web.bind.annotation.RequestBody Map<String, String> request) {
        String text = request.get("text");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text is required"));
        }
        if (text.length() > MAX_INGEST_CHARS) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "text exceeds maximum allowed size of " + MAX_INGEST_CHARS + " characters"
            ));
        }

        String documentId = UUID.nameUUIDFromBytes(text.getBytes()).toString();
        List<String> chunks = chunkingService.chunk(text);

        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            documents.add(new Document(chunks.get(i), Map.of(
                    "documentId", documentId,
                    "chunkIndex", i,
                    "totalChunks", chunks.size()
            )));
        }

        vectorStore.add(documents);
        log.info("Ingested documentId={} — {} chunks stored in Pinecone via Spring AI", documentId, chunks.size());

        return ResponseEntity.ok(Map.of(
                "documentId", documentId,
                "chunks", chunks.size()
        ));
    }

    /**
     * POST /api/rag-ai/ask
     * Body: {"question": "..."}
     *
     * vectorStore.similaritySearch() internally:
     *   1. Embeds the question via EmbeddingModel
     *   2. Searches Pinecone for top-3 results above 0.70 similarity
     *   3. Returns matching Document objects with content + metadata
     */
    @Operation(summary = "Ask a question (RAG + fallback)", description = "Embeds the question, searches Pinecone for relevant chunks (top 3, threshold 0.70). If context found, answers using RAG. If not, falls back to direct Gemini call. Response includes 'source': 'rag' or 'llm-fallback'.")
    @RequestBody(required = true, content = @Content(examples = @ExampleObject(value = "{\"question\": \"What is Spring AI?\"}")))
    @PostMapping("/ask")
    public ResponseEntity<Map<String, Object>> ask(@org.springframework.web.bind.annotation.RequestBody Map<String, String> request) {
        String question = request.get("question");
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question is required"));
        }

        List<Document> relevantDocs;
        try {
            relevantDocs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(question)
                            .topK(3)
                            .similarityThreshold(0.70)
                            .build()
            );
        } catch (Exception e) {
            // Pinecone returned vectors whose metadata doesn't match the expected schema
            // (e.g. data ingested by a different pipeline without the document_content key).
            // Treat as no usable context rather than crashing.
            log.warn("similaritySearch failed — likely a metadata schema mismatch in Pinecone: {}", e.getMessage());
            return ResponseEntity.ok(Map.of(
                    "question", question,
                    "answer", "No relevant context found for your question.",
                    "chunksUsed", 0
            ));
        }

        if (relevantDocs.isEmpty()) {
            log.info("No Pinecone context found for '{}' — falling back to direct LLM", question);
            String fallbackAnswer = chatClient.prompt()
                    .system(Prompts.FALLBACK_SYSTEM)
                    .user(question)
                    .call()
                    .content();
            return ResponseEntity.ok(Map.of(
                    "question", question,
                    "answer", fallbackAnswer,
                    "chunksUsed", 0,
                    "source", "llm-fallback"
            ));
        }

        String context = relevantDocs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));

        String answer = chatClient.prompt()
                .system(Prompts.RAG_SYSTEM)
                .user(Prompts.RAG_USER_TEMPLATE.formatted(context, question))
                .call()
                .content();

        log.info("RAG ask — question='{}', chunks used={}, source=rag", question, relevantDocs.size());

        return ResponseEntity.ok(Map.of(
                "question", question,
                "answer", answer,
                "chunksUsed", relevantDocs.size(),
                "source", "rag"
        ));
    }
}
