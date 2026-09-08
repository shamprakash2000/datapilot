package com.example.controller;

import com.example.config.ChatHistoryDialect;
import com.example.config.Prompts;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
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

@Tag(name = "MCP Agent", description = "Database query agent whose tools run in a separate MCP server (gemini-mcp-server, port 8081). " +
        "Gemini calls tools via MCP protocol over HTTP/SSE instead of in-memory @Tool methods. " +
        "All endpoints require a conversationId from POST /api/session.")
@RestController
@RequestMapping("/api/mcp-agent")
public class McpAgentController {

    private static final Logger log = LoggerFactory.getLogger(McpAgentController.class);

    @Value("${agent.timeout-seconds:30}")
    private int timeoutSeconds;

    private final ChatClient chatClient;
    private final boolean mcpAvailable;

    public McpAgentController(ChatModel chatModel, JdbcTemplate jdbcTemplate,
                               SyncMcpToolCallbackProvider mcpTools) {
        JdbcChatMemoryRepository memoryRepository = JdbcChatMemoryRepository.builder()
                .jdbcTemplate(jdbcTemplate)
                .dialect(new ChatHistoryDialect())
                .build();

        MessageWindowChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(memoryRepository)
                .maxMessages(20)
                .build();

        this.mcpAvailable = mcpTools.getToolCallbacks().length > 0;
        if (!mcpAvailable) {
            log.warn("McpAgentController: no MCP tools available — start gemini-mcp-server on port 8081");
        }

        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(Prompts.DATABASE_AGENT_SYSTEM)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
                .defaultToolCallbacks(mcpTools)
                .build();
    }

    @Operation(
        summary = "Ask the MCP-backed database agent",
        description = "Same as /api/db-agent/chat but tools execute in gemini-mcp-server (port 8081) via MCP protocol. " +
                      "Check gemini-mcp-server logs to see tool execution happening there, not here. " +
                      "Example: {\"conversationId\": \"uuid\", \"message\": \"Which city has the most orders?\"}"
    )
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> request) {
        String conversationId = request.get("conversationId");
        String message = request.get("message");

        if (!mcpAvailable) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "MCP server is not running. Start gemini-mcp-server on port 8081 and restart gemini-chat."
            ));
        }
        if (conversationId == null || conversationId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "conversationId is required. Call POST /api/session first."
            ));
        }
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message is required"));
        }

        log.info("MCP agent request — session: {}, message: {}", conversationId, message);

        try {
            String augmented = message + "\n\n[Format rule: If you call executeQuery, copy the COMPLETE table from the tool result verbatim into your response. Do not summarize, paraphrase, or omit any rows or columns.]";
            String response = CompletableFuture.supplyAsync(() ->
                    chatClient.prompt()
                            .user(augmented)
                            .advisors(a -> a.param("chat_memory_conversation_id", conversationId))
                            .call()
                            .content()
            ).orTimeout(timeoutSeconds, TimeUnit.SECONDS).join();

            log.info("MCP agent response — session: {}", conversationId);
            return ResponseEntity.ok(Map.of(
                    "conversationId", conversationId,
                    "message", message,
                    "response", response
            ));
        } catch (CompletionException e) {
            if (e.getCause() instanceof TimeoutException) {
                log.warn("MCP agent timed out after {}s — session: {}", timeoutSeconds, conversationId);
                return ResponseEntity.status(408).body(Map.of(
                        "error", "The agent took too long to respond.",
                        "conversationId", conversationId
                ));
            }
            log.error("MCP agent failed — session: {}, error: {}", conversationId, e.getCause().getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "The MCP agent encountered an error. Is gemini-mcp-server running on port 8081?",
                    "conversationId", conversationId
            ));
        }
    }

    @Operation(
        summary = "Ask the MCP-backed database agent (streaming)",
        description = "SSE streaming version of /api/mcp-agent/chat. " +
                      "Note: per-tool status events are not available here — tools run in a separate JVM (gemini-mcp-server) " +
                      "so ThreadLocal cannot bridge the two processes. Stream emits 'token' (final answer) and [DONE]."
    )
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(@RequestBody Map<String, String> request) {
        String conversationId = request.get("conversationId");
        String message = request.get("message");

        if (!mcpAvailable) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error")
                    .data("MCP server is not running. Start gemini-mcp-server on port 8081 and restart gemini-chat.")
                    .build());
        }
        if (conversationId == null || conversationId.isBlank()) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error")
                    .data("conversationId is required. Call POST /api/session first.")
                    .build());
        }
        if (message == null || message.isBlank()) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error").data("message is required").build());
        }

        log.info("MCP agent stream request — session: {}, message: {}", conversationId, message);

        return Flux.<ServerSentEvent<String>>create(sink -> {
            // No ThreadLocal status emitter here — tools run in gemini-mcp-server (different JVM).
            // We emit a single status to let the client know the agent is working.
            sink.next(ServerSentEvent.<String>builder()
                    .event("status").data("Querying database via MCP server...").build());
            try {
                // Append table instruction to the user message so Gemini treats it as a user requirement,
                // not just a system guideline — Gemini is more obedient to user turns than system prompts.
                String augmented = message + "\n\n[Format rule: If you call executeQuery, copy the COMPLETE table from the tool result verbatim into your response. Do not summarize, paraphrase, or omit any rows or columns.]";
                String response = chatClient.prompt()
                        .user(augmented)
                        .advisors(a -> a.param("chat_memory_conversation_id", conversationId))
                        .call()
                        .content();

                sink.next(ServerSentEvent.<String>builder().event("token").data(response).build());
                sink.next(ServerSentEvent.<String>builder().data("[DONE]").build());
                log.info("MCP agent stream completed — session: {}", conversationId);
            } catch (Exception e) {
                log.error("MCP agent stream error — session: {}, error: {}", conversationId, e.getMessage());
                sink.next(ServerSentEvent.<String>builder()
                        .event("error").data("Agent encountered an error. Is gemini-mcp-server running on port 8081?").build());
            } finally {
                sink.complete();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
