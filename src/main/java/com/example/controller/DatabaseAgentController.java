package com.example.controller;

import com.example.config.ChatHistoryDialect;
import com.example.config.Prompts;
import com.example.service.DatabaseTools;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Tag(name = "Database Agent", description = "Natural language database query agent. " +
        "Ask questions in plain English — the LLM runs a ReAct loop: lists tables, inspects schemas, " +
        "generates safe SELECT queries, and returns formatted results. " +
        "Only SELECT is allowed; tables are whitelisted (da_products, da_orders). " +
        "All endpoints require a conversationId from POST /api/session.")
@RestController
@RequestMapping("/api/db-agent")
public class DatabaseAgentController {

    private static final Logger log = LoggerFactory.getLogger(DatabaseAgentController.class);

    @Value("${agent.timeout-seconds:30}")
    private int timeoutSeconds;

    private final ChatClient chatClient;

    public DatabaseAgentController(ChatModel chatModel, JdbcTemplate jdbcTemplate, DatabaseTools databaseTools) {
        JdbcChatMemoryRepository memoryRepository = JdbcChatMemoryRepository.builder()
                .jdbcTemplate(jdbcTemplate)
                .dialect(new ChatHistoryDialect())
                .build();

        MessageWindowChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(memoryRepository)
                .maxMessages(20)
                .build();

        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(Prompts.DATABASE_AGENT_SYSTEM)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
                .defaultTools(databaseTools)
                .build();
    }

    @Operation(
        summary = "Ask the database agent a question",
        description = "Natural language queries against da_products and da_orders. " +
                      "The agent lists tables, checks schemas, writes SELECT SQL, and returns results. " +
                      "Hard timeout: 30s. " +
                      "Example: {\"conversationId\": \"uuid\", \"message\": \"Show top 5 products by total revenue\"}"
    )
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> request) {
        String conversationId = request.get("conversationId");
        String message = request.get("message");

        if (conversationId == null || conversationId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "conversationId is required. Call POST /api/db-agent/session first."
            ));
        }
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message is required"));
        }

        log.info("DB agent request — session: {}, message: {}", conversationId, message);

        try {
            String response = CompletableFuture.supplyAsync(() ->
                    chatClient.prompt()
                            .user(message)
                            .advisors(a -> a.param("chat_memory_conversation_id", conversationId))
                            .call()
                            .content()
            ).orTimeout(timeoutSeconds, TimeUnit.SECONDS).join();

            log.info("DB agent response — session: {}", conversationId);
            return ResponseEntity.ok(Map.of(
                    "conversationId", conversationId,
                    "message", message,
                    "response", response
            ));
        } catch (CompletionException e) {
            if (e.getCause() instanceof TimeoutException) {
                log.warn("DB agent timed out after {}s — session: {}", timeoutSeconds, conversationId);
                return ResponseEntity.status(408).body(Map.of(
                        "error", "The agent took too long to respond. Please try a simpler question.",
                        "conversationId", conversationId
                ));
            }
            log.error("DB agent failed — session: {}, error: {}", conversationId, e.getCause().getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "The database agent encountered an error. Please try again.",
                    "conversationId", conversationId
            ));
        }
    }

    @Operation(
        summary = "Ask the database agent (streaming with tool status)",
        description = "Same as /chat but streams progress as SSE. " +
                      "Event types: 'status' (fires as each tool runs — e.g. 'Getting schema for da_orders...') " +
                      "and 'token' (the final answer). Stream ends with data:[DONE]. " +
                      "Use curl or a browser EventSource to see live events — Swagger collects all at once. " +
                      "Example curl: curl -X POST http://localhost:8080/api/db-agent/chat/stream " +
                      "-H 'Content-Type: application/json' " +
                      "-d '{\"conversationId\":\"uuid\",\"message\":\"Which city has the most orders?\"}'"
    )
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(@RequestBody Map<String, String> request) {
        String conversationId = request.get("conversationId");
        String message = request.get("message");

        if (conversationId == null || conversationId.isBlank()) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error")
                    .data("conversationId is required. Call POST /api/db-agent/session first.")
                    .build());
        }
        if (message == null || message.isBlank()) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error").data("message is required").build());
        }

        log.info("DB agent stream request — session: {}, message: {}", conversationId, message);

        return Flux.<ServerSentEvent<String>>create(sink -> {
            DatabaseTools.setStatusEmitter(msg -> sink.next(
                    ServerSentEvent.<String>builder().event("status").data(msg).build()
            ));
            try {
                String response = chatClient.prompt()
                        .user(message)
                        .advisors(a -> a.param("chat_memory_conversation_id", conversationId))
                        .call()
                        .content();

                sink.next(ServerSentEvent.<String>builder().event("token").data(response).build());
                sink.next(ServerSentEvent.<String>builder().data("[DONE]").build());
                log.info("DB agent stream completed — session: {}", conversationId);
            } catch (Exception e) {
                log.error("DB agent stream error — session: {}, error: {}", conversationId, e.getMessage());
                sink.next(ServerSentEvent.<String>builder()
                        .event("error").data("Agent encountered an error. Please try again.").build());
            } finally {
                DatabaseTools.clearStatusEmitter();
                sink.complete();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
