package com.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.embedding.url}")
    private String embeddingUrl;

    @Value("${gemini.batch.embedding.url}")
    private String batchEmbeddingUrl;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Embeds a single text — used by the ask flow where only one question needs embedding.
     * Similar text = similar vectors. This is the core of semantic search.
     */
    public List<Float> embed(String text) throws Exception {
        // outputDimensionality truncates from 3072 to 768 — valid because gemini-embedding-001
        // is trained with MRL, meaning the first N dims are semantically complete on their own.
        // This keeps our Pinecone index at 768 dims without sacrificing meaningful quality.
        String requestBody = """
                {
                  "content": {
                    "parts": [{"text": "%s"}]
                  },
                  "outputDimensionality": 768
                }
                """.formatted(text.replace("\"", "\\\""));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(embeddingUrl))
                .header("Content-Type", "application/json")
                .header("X-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode values = root.path("embedding").path("values");

        List<Float> vector = new ArrayList<>();
        for (JsonNode val : values) {
            vector.add(val.floatValue());
        }

        log.info("Generated embedding with {} dimensions", vector.size());
        return vector;
    }

    /**
     * Embeds multiple texts in a single API call — used by the ingest flow for chunk batches.
     * One network round trip regardless of how many chunks, preserving order of input list.
     */
    public List<List<Float>> batchEmbed(List<String> texts) throws Exception {
        // Build the requests array — each entry wraps one chunk text
        StringBuilder requestsJson = new StringBuilder("[");
        for (int i = 0; i < texts.size(); i++) {
            String escaped = texts.get(i).replace("\"", "\\\"").replace("\n", " ");
            requestsJson.append("""
                    {"model":"models/gemini-embedding-001","content":{"parts":[{"text":"%s"}]},"outputDimensionality":768}
                    """.formatted(escaped).trim());
            if (i < texts.size() - 1) requestsJson.append(",");
        }
        requestsJson.append("]");

        String requestBody = "{\"requests\":" + requestsJson + "}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(batchEmbeddingUrl))
                .header("Content-Type", "application/json")
                .header("X-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode embeddings = root.path("embeddings");

        List<List<Float>> result = new ArrayList<>();
        for (JsonNode embedding : embeddings) {
            List<Float> vector = new ArrayList<>();
            for (JsonNode val : embedding.path("values")) {
                vector.add(val.floatValue());
            }
            result.add(vector);
        }

        log.info("Batch embedded {} texts into {} vectors of {} dimensions each",
                texts.size(), result.size(), result.isEmpty() ? 0 : result.get(0).size());
        return result;
    }
}
