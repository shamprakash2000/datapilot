package com.example.controller;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.api.url}")
    private String apiUrl;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Conversation history — stored in memory while the app is running
    private final List<Map<String, String>> history = new ArrayList<>();

    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody Map<String, String> request) throws Exception {
        String userMessage = request.get("message");

        // Add user message to history
        history.add(Map.of("role", "user", "text", userMessage));

        // Build contents array from full history
        StringBuilder contentsJson = new StringBuilder("[");
        for (int i = 0; i < history.size(); i++) {
            Map<String, String> entry = history.get(i);
            contentsJson.append("{\"role\":\"%s\",\"parts\":[{\"text\":\"%s\"}]}"
                    .formatted(entry.get("role"), escape(entry.get("text"))));
            if (i < history.size() - 1) contentsJson.append(",");
        }
        contentsJson.append("]");

        String systemPrompt = "You are a helpful assistant for a Java backend engineer learning AI development. Be concise and practical.";

        String requestBody = "{\"system_instruction\":{\"parts\":[{\"text\":\"" + escape(systemPrompt) + "\"}]},\"contents\":" + contentsJson + "}";

        log.info("Sending {} messages in history", history.size());

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .header("X-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> httpResponse = httpClient.send(
                httpRequest,
                HttpResponse.BodyHandlers.ofString()
        );

        log.info("Gemini raw response: {}", httpResponse.body());

        JsonNode root = objectMapper.readTree(httpResponse.body());
        JsonNode candidates = root.path("candidates");
        if (candidates.isEmpty()) {
            String errorMsg = root.path("error").path("message").asText("Unknown error from Gemini");
            history.remove(history.size() - 1); // remove the user message we just added
            return Map.of("question", userMessage, "error", errorMsg);
        }

        String answer = candidates.get(0)
                .path("content")
                .path("parts").get(0)
                .path("text")
                .asText();

        // Add AI answer to history so next message has full context
        history.add(Map.of("role", "model", "text", answer));

        log.info("History size: {}", history.size());

        return Map.of("question", userMessage, "answer", answer);
    }

    // Clear history — start a fresh conversation
    @PostMapping("/chat/reset")
    public Map<String, String> reset() {
        history.clear();
        return Map.of("status", "conversation cleared");
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "running", "historySize", String.valueOf(history.size()));
    }

    // Escape special characters before embedding text inside a JSON string
    private String escape(String text) {
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r");
    }
}
