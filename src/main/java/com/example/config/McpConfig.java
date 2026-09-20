package com.example.config;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Configuration
@EnableScheduling
public class McpConfig {

    private static final Logger log = LoggerFactory.getLogger(McpConfig.class);

    @Value("${spring.ai.mcp.client.sse.connections.knowledge-server.url:http://localhost:8082}")
    private String mcpServerUrl;

    private final AtomicReference<SyncMcpToolCallbackProvider> providerRef =
            new AtomicReference<>(new SyncMcpToolCallbackProvider(List.of()));

    private volatile boolean connected = false;

    @Bean
    public SyncMcpToolCallbackProvider syncMcpToolCallbackProvider() {
        SyncMcpToolCallbackProvider provider = connect();
        providerRef.set(provider);
        connected = provider.getToolCallbacks().length > 0;
        return provider;
    }

    // Retries every 30s until datapilot-mcp is reachable.
    // Stops retrying once connected — handles Render free-tier cold starts
    // where both services wake up simultaneously.
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void retryIfDisconnected() {
        if (connected) return;
        log.info("Retrying MCP connection to {}...", mcpServerUrl);
        SyncMcpToolCallbackProvider provider = connect();
        if (provider.getToolCallbacks().length > 0) {
            providerRef.set(provider);
            connected = true;
            log.info("MCP reconnected — {} tools available", provider.getToolCallbacks().length);
        }
    }

    public boolean isConnected() {
        return connected;
    }

    public SyncMcpToolCallbackProvider currentProvider() {
        return providerRef.get();
    }

    private SyncMcpToolCallbackProvider connect() {
        if (mcpServerUrl.isBlank()) {
            log.warn("MCP server URL not configured — MCP agent endpoints are disabled");
            return new SyncMcpToolCallbackProvider(List.of());
        }
        try {
            // Step 1: ping /actuator/health with a long timeout to wake Render free-tier service.
            // Render cold start can take 30-60s — this forces the service awake before we open SSE.
            String healthUrl = mcpServerUrl.replaceAll("/+$", "") + "/actuator/health";
            log.info("Pinging datapilot-mcp health endpoint: {}", healthUrl);
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(90))
                    .build();
            HttpRequest healthRequest = HttpRequest.newBuilder()
                    .uri(URI.create(healthUrl))
                    .timeout(Duration.ofSeconds(90))
                    .GET()
                    .build();
            HttpResponse<String> healthResponse = httpClient.send(healthRequest,
                    HttpResponse.BodyHandlers.ofString());
            if (healthResponse.statusCode() != 200) {
                log.warn("datapilot-mcp health returned {}: {}", healthResponse.statusCode(), healthResponse.body());
                return new SyncMcpToolCallbackProvider(List.of());
            }
            log.info("datapilot-mcp is healthy — establishing MCP SSE connection");

            // Step 2: now the service is awake, open the SSE/MCP connection
            HttpClientSseClientTransport transport = HttpClientSseClientTransport
                    .builder(mcpServerUrl)
                    .build();
            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(60))
                    .build();
            client.initialize();
            log.info("MCP client connected to {}", mcpServerUrl);
            return new SyncMcpToolCallbackProvider(List.of(client));
        } catch (Exception e) {
            log.warn("MCP server at {} is not reachable: {}", mcpServerUrl, e.getMessage());
            return new SyncMcpToolCallbackProvider(List.of());
        }
    }
}
