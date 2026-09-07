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

import java.time.Duration;
import java.util.List;

@Configuration
public class McpConfig {

    private static final Logger log = LoggerFactory.getLogger(McpConfig.class);

    @Value("${spring.ai.mcp.client.sse.connections.db-server.url:}")
    private String mcpServerUrl;

    // Called at startup (bean init) and again on /api/mcp-agent/reconnect.
    // Returns a provider with live tools on success, empty provider on failure.
    public SyncMcpToolCallbackProvider connect() {
        if (mcpServerUrl.isBlank()) {
            log.warn("MCP server URL not configured — MCP agent endpoints are disabled");
            return new SyncMcpToolCallbackProvider(List.of());
        }
        try {
            HttpClientSseClientTransport transport = HttpClientSseClientTransport
                    .builder(mcpServerUrl)
                    .build();
            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(10))
                    .build();
            client.initialize();
            log.info("MCP client connected to {}", mcpServerUrl);
            return new SyncMcpToolCallbackProvider(List.of(client));
        } catch (Exception e) {
            log.warn("MCP server at {} is not running — MCP agent endpoints will return 503: {}",
                    mcpServerUrl, e.getMessage());
            return new SyncMcpToolCallbackProvider(List.of());
        }
    }

    @Bean
    public SyncMcpToolCallbackProvider syncMcpToolCallbackProvider() {
        return connect();
    }
}
