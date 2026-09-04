package com.example.controller;

import com.example.config.Prompts;
import com.example.service.AgentTools;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import com.example.config.ChatHistoryDialect;

import java.util.Map;
import java.util.UUID;

@Tag(name = "Travel Agent", description = "Agentic travel planner — the LLM autonomously calls weather, attractions, and budget tools to build a personalised itinerary.")
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final ChatClient chatClient;
    private final AgentTools agentTools;

    public AgentController(ChatClient.Builder builder, JdbcTemplate jdbcTemplate, AgentTools agentTools) {
        this.agentTools = agentTools;

        JdbcChatMemoryRepository memoryRepository = JdbcChatMemoryRepository.builder()
                .jdbcTemplate(jdbcTemplate)
                .dialect(new ChatHistoryDialect())
                .build();

        MessageWindowChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(memoryRepository)
                .maxMessages(20)
                .build();

        // Register tools at build time — Gemini will decide when to call them
        this.chatClient = builder
                .defaultSystem(Prompts.TRAVEL_AGENT_SYSTEM)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
                .defaultTools(agentTools)
                .build();
    }

    @Operation(
        summary = "Start a new travel session",
        description = "Returns a conversationId to use in all subsequent /chat calls."
    )
    @PostMapping("/session")
    public Map<String, String> startSession() {
        String conversationId = UUID.randomUUID().toString();
        log.info("New travel agent session: {}", conversationId);
        return Map.of("conversationId", conversationId);
    }

    @Operation(
        summary = "Chat with the travel agent",
        description = "Send a message to the travel agent. The agent autonomously calls weather, attractions, and budget tools as needed. Example: 'Plan a 3-day trip to Goa in October'"
    )
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> request) {
        String conversationId = request.get("conversationId");
        String message = request.get("message");

        if (conversationId == null || conversationId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "conversationId is required. Call POST /api/agent/session first."
            ));
        }
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message is required"));
        }

        log.info("Travel agent request — session: {}, message: {}", conversationId, message);

        String response = chatClient.prompt()
                .user(message)
                .advisors(a -> a.param("chat_memory_conversation_id", conversationId))
                .call()
                .content();

        log.info("Travel agent response generated for session: {}", conversationId);

        return ResponseEntity.ok(Map.of(
                "conversationId", conversationId,
                "message", message,
                "response", response
        ));
    }

    @Operation(
        summary = "Clear travel session history",
        description = "Deletes conversation history for this session from PostgreSQL."
    )
    @DeleteMapping("/session/{conversationId}")
    public Map<String, String> deleteSession(@PathVariable String conversationId) {
        log.info("Travel agent session deleted: {}", conversationId);
        return Map.of("status", "session cleared", "conversationId", conversationId);
    }
}
