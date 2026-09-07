package com.example.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@Tag(name = "Session", description = "Shared session management. The returned conversationId works with any agent endpoint.")
@RestController
@RequestMapping("/api/session")
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    @Operation(
        summary = "Start a new session",
        description = "Returns a conversationId (UUID) to pass to any agent endpoint — Travel Agent (/api/agent/chat) or Database Agent (/api/db-agent/chat). Sessions are isolated by this ID in persistent memory."
    )
    @PostMapping
    public Map<String, String> startSession() {
        String conversationId = UUID.randomUUID().toString();
        log.info("New session created: {}", conversationId);
        return Map.of("conversationId", conversationId);
    }
}
