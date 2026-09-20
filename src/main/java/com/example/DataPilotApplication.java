package com.example;

import org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration;
import org.springframework.ai.mcp.client.common.autoconfigure.McpToolCallbackAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Exclude Spring AI's fail-fast MCP auto-configs.
// By default they call initialize() at startup — if the MCP server is down
// the entire Spring context fails. Our McpConfig provides a resilient replacement.
@SpringBootApplication(exclude = {McpClientAutoConfiguration.class, McpToolCallbackAutoConfiguration.class})
public class DataPilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataPilotApplication.class, args);
        System.out.println("Application started");
    }
}
