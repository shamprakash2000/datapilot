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
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

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
                .defaultSystem("You are a helpful assistant for a Java backend engineer learning AI development. Be concise and practical.")
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
                .build();
    }

    /**
     * Start a new conversation — returns a UUID the client must pass on every subsequent message.
     * POST /api/chat-ai/session
     */
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
    @PostMapping("/chat-ai")
    public ResponseEntity<Map<String, String>> chat(@RequestBody Map<String, String> request) {
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
    @DeleteMapping("/chat-ai/session/{conversationId}")
    public Map<String, String> deleteSession(@PathVariable String conversationId) {
        memoryRepository.deleteByConversationId(conversationId);
        log.info("Conversation history deleted: {}", conversationId);
        return Map.of("status", "conversation deleted", "conversationId", conversationId);
    }
}
