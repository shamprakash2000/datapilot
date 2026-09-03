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
import com.example.model.ChunkData;

import java.util.ArrayList;
import java.util.List;

@Service
public class PineconeService {

    private static final Logger log = LoggerFactory.getLogger(PineconeService.class);

    @Value("${pinecone.api.key}")
    private String apiKey;

    @Value("${pinecone.host}")
    private String host;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Stores a chunk and its vector in Pinecone.
     * id         — deterministic chunk ID (documentId + chunk index)
     * vector     — the 768-number embedding of the chunk text
     * text       — chunk content returned during search to build LLM context
     * documentId — links all chunks back to their source document
     * chunkIndex — position of this chunk within the document
     * totalChunks — total chunks in the document (useful for reconstruction)
     */
    public void upsert(String id, List<Float> vector, String text,
                       String documentId, int chunkIndex, int totalChunks) throws Exception {
        String vectorJson = vector.toString(); // [0.1, 0.2, ...]
        String safeText = text.replace("\"", "\\\"").replace("\n", " ");

        String requestBody = """
                {
                  "vectors": [
                    {
                      "id": "%s",
                      "values": %s,
                      "metadata": {
                        "text": "%s",
                        "documentId": "%s",
                        "chunkIndex": %d,
                        "totalChunks": %d
                      }
                    }
                  ]
                }
                """.formatted(id, vectorJson, safeText, documentId, chunkIndex, totalChunks);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(host + "/vectors/upsert"))
                .header("Content-Type", "application/json")
                .header("Api-Key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.info("Pinecone upsert response for chunk {}/{}: {}", chunkIndex + 1, totalChunks, response.body());
    }

    /**
     * Stores all chunks of a document in one Pinecone API call.
     * Single round trip regardless of chunk count — avoids N sequential upsert calls.
     */
    public void batchUpsert(List<ChunkData> chunks) throws Exception {
        StringBuilder vectorsJson = new StringBuilder("[");
        for (int i = 0; i < chunks.size(); i++) {
            ChunkData chunk = chunks.get(i);
            String safeText = chunk.text().replace("\"", "\\\"").replace("\n", " ");
            vectorsJson.append("""
                    {"id":"%s","values":%s,"metadata":{"text":"%s","documentId":"%s","chunkIndex":%d,"totalChunks":%d}}
                    """.formatted(
                    chunk.id(), chunk.vector().toString(), safeText,
                    chunk.documentId(), chunk.chunkIndex(), chunk.totalChunks()
            ).trim());
            if (i < chunks.size() - 1) vectorsJson.append(",");
        }
        vectorsJson.append("]");

        String requestBody = "{\"vectors\":" + vectorsJson + "}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(host + "/vectors/upsert"))
                .header("Content-Type", "application/json")
                .header("Api-Key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.info("Pinecone batch upsert response ({} chunks): {}", chunks.size(), response.body());
    }

    /**
     * Searches Pinecone for the top K most similar documents to the query vector.
     * Returns the original text of each matching document.
     */
    public List<String> search(List<Float> queryVector, int topK) throws Exception {
        String vectorJson = queryVector.toString();

        String requestBody = """
                {
                  "vector": %s,
                  "topK": %d,
                  "includeMetadata": true
                }
                """.formatted(vectorJson, topK);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(host + "/query"))
                .header("Content-Type", "application/json")
                .header("Api-Key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode matches = root.path("matches");

        // 0.70 threshold — below this the match is semantically too distant to be useful.
        // Without this, unrelated documents get sent to Gemini causing hallucinated answers.
        final float SCORE_THRESHOLD = 0.70f;

        List<String> results = new ArrayList<>();
        for (JsonNode match : matches) {
            float score = match.path("score").floatValue();
            log.info("Match score: {}", score);
            if (score < SCORE_THRESHOLD) {
                log.info("Skipping match — score {} below threshold {}", score, SCORE_THRESHOLD);
                continue;
            }
            String text = match.path("metadata").path("text").asText();
            results.add(text);
        }

        return results;
    }
}
