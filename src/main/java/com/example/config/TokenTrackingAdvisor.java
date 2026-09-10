package com.example.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

/**
 * Logs a structured line for every LLM call:
 *
 *   AI_CALL | session=abc | model=gemini-3.5-flash-lite | in=823 | out=412 | total=1235 | latency=1247ms | cost=$0.000156
 *
 * Cost formula (Gemini 3.5 Flash Lite pricing):
 *   Input:  $0.000075 / 1K tokens
 *   Output: $0.000300 / 1K tokens
 * Update COST_PER_1K_* when you switch models.
 */
@Component
public class TokenTrackingAdvisor {

    private static final Logger log = LoggerFactory.getLogger(TokenTrackingAdvisor.class);

    private static final double INPUT_COST_PER_1K  = 0.000075;
    private static final double OUTPUT_COST_PER_1K = 0.000300;

    public void record(String sessionId, ChatResponse chatResponse, long latencyMs) {
        try {
            if (chatResponse == null || chatResponse.getMetadata() == null) {
                log.info("AI_CALL | session={} | latency={}ms | metadata=unavailable", sessionId, latencyMs);
                return;
            }

            String model = chatResponse.getMetadata().getModel();
            Usage usage  = chatResponse.getMetadata().getUsage();

            if (usage == null) {
                log.info("AI_CALL | session={} | model={} | latency={}ms | tokens=unavailable",
                        sessionId, model, latencyMs);
                return;
            }

            long inputTokens  = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0L;
            long totalTokens  = usage.getTotalTokens()  != null ? usage.getTotalTokens()  : inputTokens;
            long outputTokens = totalTokens - inputTokens;
            double costUsd    = (inputTokens / 1000.0 * INPUT_COST_PER_1K)
                              + (outputTokens / 1000.0 * OUTPUT_COST_PER_1K);

            log.info("AI_CALL | session={} | model={} | in={} | out={} | total={} | latency={}ms | cost=${}",
                    sessionId,
                    model != null ? model : "unknown",
                    inputTokens, outputTokens, totalTokens,
                    latencyMs,
                    String.format("%.6f", costUsd));

        } catch (Exception e) {
            log.warn("TokenTrackingAdvisor: failed to extract metrics — {}", e.getMessage());
        }
    }
}
