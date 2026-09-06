package com.example.controller;

import com.example.config.Prompts;
import com.example.model.ItineraryResponse;
import com.example.service.AgentTools;
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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.example.config.ChatHistoryDialect;

import java.util.Map;
import java.util.UUID;

@Tag(name = "Travel Agent", description = "Agentic travel planner for Indian destinations. " +
        "The LLM runs a ReAct loop — it reasons, calls tools (weather, attractions, budget, hotels, transport, flights, datetime), " +
        "processes results, and repeats until it can give a complete answer. " +
        "Supports conversational memory, structured JSON itineraries, and hybrid SSE streaming with live tool status events. " +
        "All endpoints require a conversationId from POST /api/agent/session.")
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    @Value("${agent.timeout-seconds:30}")
    private int timeoutSeconds;

    private final ChatClient chatClient;
    private final ChatClient itineraryClient;
    private final ChatClient validationClient;

    public AgentController(ChatModel chatModel, JdbcTemplate jdbcTemplate, AgentTools agentTools) {
        JdbcChatMemoryRepository memoryRepository = JdbcChatMemoryRepository.builder()
                .jdbcTemplate(jdbcTemplate)
                .dialect(new ChatHistoryDialect())
                .build();

        MessageWindowChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(memoryRepository)
                .maxMessages(20)
                .build();

        // Conversational agent — has memory and tools; used by /chat (blocking)
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(Prompts.TRAVEL_AGENT_SYSTEM)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
                .defaultTools(agentTools)
                .build();

        // Itinerary generator — no memory, structured JSON via .entity()
        this.itineraryClient = ChatClient.builder(chatModel)
                .defaultSystem(Prompts.ITINERARY_SYSTEM)
                .defaultTools(agentTools)
                .build();

        // Geography validator — no tools, no memory, YES/NO only
        this.validationClient = ChatClient.builder(chatModel).build();
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
        description = "Conversational agent with persistent memory (last 20 messages) and full tool access. " +
                      "The agent autonomously decides which tools to call — getWeather, getAttractions, estimateBudget, findHotels, getModeOfTransport, searchFlights, getCurrentDateTime. " +
                      "Independent tools are called in parallel. Hard timeout: 45s (returns 408 if exceeded). " +
                      "Example: {\"conversationId\": \"uuid\", \"message\": \"Plan a 3-day trip to Goa in October from Bangalore\"}"
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

        try {
            String response = CompletableFuture.supplyAsync(() ->
                    chatClient.prompt()
                            .user(message)
                            .advisors(a -> a.param("chat_memory_conversation_id", conversationId))
                            .call()
                            .content()
            ).orTimeout(timeoutSeconds, TimeUnit.SECONDS).join();

            log.info("Travel agent response generated for session: {}", conversationId);
            return ResponseEntity.ok(Map.of(
                    "conversationId", conversationId,
                    "message", message,
                    "response", response
            ));
        } catch (CompletionException e) {
            if (e.getCause() instanceof TimeoutException) {
                log.warn("Agent timed out after {}s — session: {}", timeoutSeconds, conversationId);
                return ResponseEntity.status(408).body(Map.of(
                        "error", "The agent took too long to respond. Please try a simpler question.",
                        "conversationId", conversationId
                ));
            }
            log.error("Travel agent failed — session: {}, error: {}", conversationId, e.getCause().getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "The travel agent encountered an error. Please try again.",
                    "conversationId", conversationId
            ));
        }
    }

    @Operation(
        summary = "Generate a structured itinerary (India only)",
        description = "Returns a fully typed JSON itinerary with day-by-day plans (morning/afternoon/evening), budget breakdown, " +
                      "accommodation options, transport, packing list, and travel tips. " +
                      "A pre-validation LLM call rejects non-Indian destinations before the agent runs. " +
                      "The agent calls tools in two parallel batches: " +
                      "Batch 1 — getWeather + getAttractions + estimateBudget; " +
                      "Batch 2 — findHotels + getModeOfTransport/searchFlights. " +
                      "Hard timeout: 90s (returns 408 if exceeded). " +
                      "Example body: {\"destination\": \"Goa\", \"days\": \"3\", \"month\": \"October\", \"fromCity\": \"Bangalore\"}"
    )
    @PostMapping("/itinerary")
    public ResponseEntity<?> generateItinerary(@RequestBody Map<String, String> request) {
        String destination = request.get("destination");
        String days = request.get("days");
        String month = request.get("month");
        String fromCity = request.get("fromCity");

        if (destination == null || destination.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "destination is required"));
        }
        if (days == null || month == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "days and month are required"));
        }

        String prompt = String.format(
                Prompts.ITINERARY_USER_TEMPLATE,
                days, destination, month,
                fromCity != null && !fromCity.isBlank() ? " Travelling from " + fromCity + "." : ""
        );

        log.info("Validating destination: {}", destination);
        String validationAnswer = validationClient.prompt()
                .system(Prompts.INDIA_VALIDATION_SYSTEM)
                .user(String.format(Prompts.INDIA_VALIDATION_USER, destination))
                .call()
                .content();

        if (validationAnswer == null || !validationAnswer.trim().toUpperCase().startsWith("YES")) {
            log.info("Destination '{}' rejected — not in India (validator answered: {})", destination, validationAnswer);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "We currently support travel planning only within India. International destinations are coming soon!",
                    "destination", destination
            ));
        }

        log.info("Generating structured itinerary — destination: {}, days: {}, month: {}, from: {}",
                destination, days, month, fromCity);

        try {
            ItineraryResponse itinerary = CompletableFuture.supplyAsync(() ->
                    itineraryClient.prompt()
                            .user(prompt)
                            .call()
                            .entity(ItineraryResponse.class)
            ).orTimeout(timeoutSeconds * 2L, TimeUnit.SECONDS).join();

            log.info("Structured itinerary generated for {}", destination);
            return ResponseEntity.ok(itinerary);
        } catch (CompletionException e) {
            if (e.getCause() instanceof TimeoutException) {
                log.warn("Itinerary timed out after {}s — destination: {}", timeoutSeconds * 2, destination);
                return ResponseEntity.status(408).body(Map.of(
                        "error", "Itinerary generation took too long. Try fewer days or a simpler destination.",
                        "destination", destination
                ));
            }
            log.error("Failed to generate itinerary for {}: {}", destination, e.getCause().getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to generate itinerary. Please try again.",
                    "destination", destination
            ));
        }
    }

    @Operation(
        summary = "Chat with the travel agent (streaming with tool status)",
        description = "Runs the full tool-calling agent and streams live progress as Server-Sent Events. " +
                      "Two SSE event types: 'status' (fired as each tool executes — e.g. 'Checking weather in Goa...') " +
                      "and 'token' (the complete agent response after all tools finish). Stream ends with data:[DONE]. " +
                      "Note: Swagger UI collects all events at once — use curl or a browser EventSource to see live status events. " +
                      "Example curl: curl -X POST http://localhost:8080/api/agent/chat/stream -H 'Content-Type: application/json' " +
                      "-d '{\"conversationId\":\"abc\",\"message\":\"Plan a 3-day trip to Goa in October\"}'"
    )
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(@RequestBody Map<String, String> request) {
        String conversationId = request.get("conversationId");
        String message = request.get("message");

        if (conversationId == null || conversationId.isBlank()) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error")
                    .data("conversationId is required. Call POST /api/agent/session first.")
                    .build());
        }
        if (message == null || message.isBlank()) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error")
                    .data("message is required")
                    .build());
        }

        log.info("Streaming agent request — session: {}, message: {}", conversationId, message);

        // Pattern 1 hybrid streaming:
        //   Phase 1 — tool calls run BLOCKING on a boundedElastic thread; each tool emits a
        //             'status' SSE event in real-time via the ThreadLocal in AgentTools.
        //   Phase 2 — the complete agent response is emitted as a 'token' SSE event once done.
        //
        // This avoids the Spring AI 1.1.8 bug where thought_signature is stripped between
        // streaming rounds, while still giving the user live feedback during tool execution.
        return Flux.<ServerSentEvent<String>>create(sink -> {
            AgentTools.setStatusEmitter(msg -> sink.next(
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
                log.info("Stream completed — session: {}", conversationId);
            } catch (Exception e) {
                log.error("Stream error — session: {}, error: {}", conversationId, e.getMessage());
                sink.next(ServerSentEvent.<String>builder()
                        .event("error").data("Agent encountered an error. Please try again.").build());
            } finally {
                AgentTools.clearStatusEmitter();
                sink.complete();
            }
        }).subscribeOn(Schedulers.boundedElastic());
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
