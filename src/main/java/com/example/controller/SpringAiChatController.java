package com.example.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import com.example.config.ChatHistoryDialect;
import com.example.config.Prompts;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Tag(name = "Spring AI Chat", description = "Persistent multi-turn chat using Spring AI ChatClient. Conversation history stored in PostgreSQL (Neon). Each conversation is isolated by a UUID session ID.")
@RestController
@RequestMapping("/api")
public class SpringAiChatController {

    private static final Logger log = LoggerFactory.getLogger(SpringAiChatController.class);

    private final ChatClient chatClient;
    private final ChatMemoryRepository memoryRepository;

    public SpringAiChatController(ChatClient.Builder builder, JdbcTemplate jdbcTemplate) {
        // JdbcChatMemoryRepository persists every message to PostgreSQL.
        // Spring AI auto-creates the required table (spring_ai_chat_memory) on startup.
        this.memoryRepository = JdbcChatMemoryRepository.builder()
                .jdbcTemplate(jdbcTemplate)
                // use chat_history table instead of Spring AI's default SPRING_AI_CHAT_MEMORY
                .dialect(new ChatHistoryDialect())
                .build();

        // Keep only the last 20 messages per conversation — older ones are evicted to control token cost
        MessageWindowChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(memoryRepository)
                .maxMessages(20)
                .build();

        this.chatClient = builder
                .defaultSystem(Prompts.CHAT_AI_SYSTEM)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
                .build();
    }

    /**
     * Start a new conversation — returns a UUID the client must pass on every subsequent message.
     * POST /api/chat-ai/session
     */
    @Operation(summary = "Start a new conversation", description = "Returns a conversationId UUID. Pass this in every subsequent /chat-ai request to maintain history.")
    @PostMapping("/chat-ai/session")
    public Map<String, String> startSession() {
        String conversationId = UUID.randomUUID().toString();
        log.info("New conversation started: {}", conversationId);
        return Map.of("conversationId", conversationId);
    }

    /**
     * Send a message in an existing conversation.
     * POST /api/chat-ai
     * Body: {"conversationId": "uuid", "message": "your question"}
     */
    @Operation(summary = "Send a message", description = "Send a message in an existing conversation. History (last 20 messages) is loaded from PostgreSQL and sent to Gemini automatically.")
    @RequestBody(required = true, content = @Content(examples = @ExampleObject(value = "{\"conversationId\": \"your-uuid-here\", \"message\": \"What did I just ask you?\"}")))
    @PostMapping("/chat-ai")
    public ResponseEntity<Map<String, String>> chat(@org.springframework.web.bind.annotation.RequestBody Map<String, String> request) {
        String conversationId = request.get("conversationId");
        String userMessage = request.get("message");

        if (conversationId == null || conversationId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "conversationId is required. Call POST /api/chat-ai/session first."));
        }
        if (userMessage == null || userMessage.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "message is required."));
        }

        log.info("Chat request — conversationId: {}, message: {}", conversationId, userMessage);

        String answer = chatClient.prompt()
                .user(userMessage)
                // each conversation ID maps to its own isolated history in PostgreSQL
                .advisors(a -> a.param("chat_memory_conversation_id", conversationId))
                .call()
                .content();

        log.info("Response received for conversationId: {}", conversationId);
        return ResponseEntity.ok(Map.of(
                "conversationId", conversationId,
                "question", userMessage,
                "answer", answer
        ));
    }

    /**
     * Clear history for a specific conversation.
     * DELETE /api/chat-ai/session/{conversationId}
     */
    @Operation(summary = "Delete a conversation", description = "Permanently deletes all messages for this conversationId from PostgreSQL.")
    @DeleteMapping("/chat-ai/session/{conversationId}")
    public Map<String, String> deleteSession(@PathVariable String conversationId) {
        memoryRepository.deleteByConversationId(conversationId);
        log.info("Conversation history deleted: {}", conversationId);
        return Map.of("status", "conversation deleted", "conversationId", conversationId);
    }
}
