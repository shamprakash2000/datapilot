package com.example.controller;

import com.example.config.ChatHistoryDialect;
import com.example.config.InputGuardrailService;
import com.example.config.InputGuardrailService.GuardrailException;
import com.example.config.OutputGuardrailService;
import com.example.config.Prompts;
import com.example.config.TokenTrackingAdvisor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
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

@Tag(name = "MCP Agent", description = "Database query agent whose tools run in a separate MCP server (datapilot-mcp, port 8082). " +
        "The LLM calls tools via MCP protocol over HTTP/SSE instead of in-memory @Tool methods. " +
        "All endpoints require a conversationId from POST /api/session.")
@RestController
@RequestMapping("/api/mcp-agent")
public class McpAgentController {

    private static final Logger log = LoggerFactory.getLogger(McpAgentController.class);

    @Value("${agent.timeout-seconds:30}")
    private int timeoutSeconds;

    private final ChatClient chatClient;
    private final TokenTrackingAdvisor tokenTracker;
    private final InputGuardrailService guardrail;
    private final OutputGuardrailService outputGuardrail;
    private final boolean mcpAvailable;

    public McpAgentController(ChatModel chatModel, JdbcTemplate jdbcTemplate,
                               SyncMcpToolCallbackProvider mcpTools,
                               TokenTrackingAdvisor tokenTracker,
                               InputGuardrailService guardrail,
                               OutputGuardrailService outputGuardrail) {
        this.tokenTracker = tokenTracker;
        this.guardrail = guardrail;
        this.outputGuardrail = outputGuardrail;

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
            log.warn("McpAgentController: no MCP tools available — start datapilot-mcp on port 8082");
        }

        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(Prompts.DATABASE_AGENT_SYSTEM)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
                .defaultToolCallbacks(mcpTools)
                .build();
    }

    @Operation(
        summary = "Ask the MCP-backed database agent",
        description = "Same as /api/db-agent/chat but tools execute in datapilot-mcp (port 8082) via MCP protocol. " +
                      "Example: {\"conversationId\": \"uuid\", \"message\": \"Which city has the most orders?\"}"
    )
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> request) {
        String conversationId = request.get("conversationId");
        String message = request.get("message");

        if (!mcpAvailable) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "MCP server is not running. Start datapilot-mcp on port 8082 and restart DataPilot."
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

        try {
            guardrail.validate(message, conversationId);
        } catch (GuardrailException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        log.info("MCP agent request — session: {}, message: {}", conversationId, message);

        try {
            String augmented = message + "\n\n[Format rule: If you call executeQuery, copy the COMPLETE table from the tool result verbatim into your response. Do not summarize, paraphrase, or omit any rows or columns.]";
            long start = System.currentTimeMillis();

            // Use chatResponse() instead of content() so we can read token usage metadata
            final String finalConversationId = conversationId;
            ChatResponse chatResponse = CompletableFuture.supplyAsync(() ->
                    chatClient.prompt()
                            .user(augmented)
                            .advisors(a -> a.param("chat_memory_conversation_id", finalConversationId))
                            .call()
                            .chatResponse()
            ).orTimeout(timeoutSeconds, TimeUnit.SECONDS).join();

            tokenTracker.record(conversationId, chatResponse, System.currentTimeMillis() - start);

            String rawResponse = chatResponse.getResult().getOutput().getText();
            OutputGuardrailService.GuardrailResult outputResult = outputGuardrail.validate(rawResponse, conversationId);
            String response = outputResult.content();
            log.info("MCP agent response — session: {}, output_guardrail: {}", conversationId, outputResult.type());
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
                    "error", "The MCP agent encountered an error. Is datapilot-mcp running on port 8082?",
                    "conversationId", conversationId
            ));
        }
    }

    @Operation(
        summary = "Ask the MCP-backed database agent (streaming)",
        description = "SSE streaming version of /api/mcp-agent/chat. " +
                      "Stream emits 'status', 'token' (final answer), and [DONE]."
    )
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(@RequestBody Map<String, String> request) {
        String conversationId = request.get("conversationId");
        String message = request.get("message");

        if (!mcpAvailable) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error")
                    .data("MCP server is not running. Start datapilot-mcp on port 8082 and restart DataPilot.")
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

        try {
            guardrail.validate(message, conversationId);
        } catch (GuardrailException e) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error").data(e.getMessage()).build());
        }

        log.info("MCP agent stream request — session: {}, message: {}", conversationId, message);

        return Flux.<ServerSentEvent<String>>create(sink -> {
            sink.next(ServerSentEvent.<String>builder()
                    .event("status").data("Querying database via MCP server...").build());
            try {
                String augmented = message + "\n\n[Format rule: If you call executeQuery, copy the COMPLETE table from the tool result verbatim into your response. Do not summarize, paraphrase, or omit any rows or columns.]";
                long start = System.currentTimeMillis();

                ChatResponse chatResponse = chatClient.prompt()
                        .user(augmented)
                        .advisors(a -> a.param("chat_memory_conversation_id", conversationId))
                        .call()
                        .chatResponse();

                tokenTracker.record(conversationId, chatResponse, System.currentTimeMillis() - start);

                String rawResponse = chatResponse.getResult().getOutput().getText();
                OutputGuardrailService.GuardrailResult outputResult = outputGuardrail.validate(rawResponse, conversationId);
                String response = outputResult.content();
                log.info("MCP agent stream completed — session: {}, output_guardrail: {}", conversationId, outputResult.type());
                sink.next(ServerSentEvent.<String>builder().event("token").data(response).build());
                sink.next(ServerSentEvent.<String>builder().data("[DONE]").build());
            } catch (Exception e) {
                log.error("MCP agent stream error — session: {}, error: {}", conversationId, e.getMessage());
                sink.next(ServerSentEvent.<String>builder()
                        .event("error").data("Agent encountered an error. Is datapilot-mcp running on port 8082?").build());
            } finally {
                sink.complete();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
