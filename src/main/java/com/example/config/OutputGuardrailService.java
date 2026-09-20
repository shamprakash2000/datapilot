package com.example.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Output guardrail — runs on every LLM response before it reaches the user.
 *
 * Two actions:
 *   BLOCK  — discard the entire response and return a safe generic message.
 *            Used when the response itself is the problem (system prompt leaked,
 *            stack trace present, infrastructure details exposed).
 *   REDACT — replace only the sensitive part, return the rest.
 *            Used when the response is mostly valid but contains a sensitive pattern
 *            (PII, credit card) that should be removed without losing the useful content.
 *
 * Why output guardrails are necessary even with input guardrails:
 *   - Input guardrails block known attack patterns in user messages.
 *   - Output guardrails catch what the model produces regardless of input — the model
 *     can leak system prompt content via indirect prompting, surface DB error details,
 *     or echo sensitive data from tool results that passed input validation cleanly.
 */
@Service
public class OutputGuardrailService {

    private static final Logger log = LoggerFactory.getLogger(OutputGuardrailService.class);

    private static final int MAX_RESPONSE_LENGTH = 5000;

    // Phrases unique to our system prompt — if these appear in a response the model
    // is echoing its own instructions back to the user.
    private static final List<String> SYSTEM_PROMPT_KEYWORDS = List.of(
            "you have five tools",
            "always follow this sequence",
            "only write select queries",
            "never guess column names",
            "database_agent_system",
            "call listtables",
            "call gettableschema"
    );

    // Phrases that indicate the model is answering a system prompt extraction attempt.
    private static final List<String> EXTRACTION_RESPONSE_PATTERNS = List.of(
            "my instructions are",
            "my system prompt",
            "i was instructed to",
            "i am instructed to",
            "my programming tells me",
            "according to my instructions",
            "as per my instructions",
            "i have been told to"
    );

    // Infrastructure strings that should never appear in user-facing output.
    private static final List<String> INFRASTRUCTURE_PATTERNS = List.of(
            "jdbc:postgresql://",
            "jdbc:mysql://",
            "spring.datasource",
            "hikaricp",
            "neon_host",
            "neon_db",
            "pinecone.api.key",
            "gemini.api.key",
            "c:\\users\\",
            "/home/",
            "c:\\program files"
    );

    // Stack trace signals — model echoed an internal exception.
    private static final List<String> STACK_TRACE_PATTERNS = List.of(
            "at com.example.",
            "at org.springframework.",
            "at java.lang.",
            "nullpointerexception",
            "illegalargumentexception",
            "caused by:",
            "exception in thread"
    );

    // Regex patterns for PII — these REDACT rather than BLOCK.
    // Credit card: 16 digits in groups of 4, separator (space or dash) required between groups.
    // The separator must be present so that long decimal numbers (e.g. PostgreSQL AVG results)
    // are not falsely matched as credit card numbers.
    private static final Pattern CREDIT_CARD = Pattern.compile(
            "\\b\\d{4}[\\s\\-]\\d{4}[\\s\\-]\\d{4}[\\s\\-]\\d{4}\\b"
    );
    // SSN: 3-2-4 digit format.
    private static final Pattern SSN = Pattern.compile(
            "\\b\\d{3}-\\d{2}-\\d{4}\\b"
    );
    // Passwords in key=value format: password=abc123, pwd=secret
    private static final Pattern PASSWORD_KV = Pattern.compile(
            "(?i)(password|passwd|pwd|secret|api[_-]?key)\\s*[=:]\\s*\\S+",
            Pattern.CASE_INSENSITIVE
    );

    // Safe message returned when a response is fully blocked.
    private static final String BLOCKED_RESPONSE =
            "I encountered an issue generating a safe response. Please rephrase your question.";

    /**
     * Validates and sanitises the LLM response.
     *
     * @param response     the raw LLM output
     * @param sessionId    used for logging only
     * @return             a {@link GuardrailResult} — either the original, redacted, or blocked
     */
    public GuardrailResult validate(String response, String sessionId) {
        if (response == null || response.isBlank()) {
            return GuardrailResult.pass("");
        }

        // 1. Length check — a runaway model or loop produces unusually long responses
        if (response.length() > MAX_RESPONSE_LENGTH) {
            log.warn("OUTPUT_GUARDRAIL_BLOCKED | reason=response_too_long | length={} | session={}",
                    response.length(), sessionId);
            return GuardrailResult.block("response_too_long");
        }

        String lower = response.toLowerCase();

        // 2. System prompt leakage — model is echoing its own instructions
        for (String keyword : SYSTEM_PROMPT_KEYWORDS) {
            if (lower.contains(keyword)) {
                log.warn("OUTPUT_GUARDRAIL_BLOCKED | reason=system_prompt_leak | keyword='{}' | session={}",
                        keyword, sessionId);
                return GuardrailResult.block("system_prompt_leak");
            }
        }

        // 3. Extraction response — model answered a prompt injection that slipped through
        for (String pattern : EXTRACTION_RESPONSE_PATTERNS) {
            if (lower.contains(pattern)) {
                log.warn("OUTPUT_GUARDRAIL_BLOCKED | reason=extraction_response | pattern='{}' | session={}",
                        pattern, sessionId);
                return GuardrailResult.block("extraction_response");
            }
        }

        // 4. Infrastructure leakage — JDBC URL, API key names, internal paths
        for (String pattern : INFRASTRUCTURE_PATTERNS) {
            if (lower.contains(pattern)) {
                log.warn("OUTPUT_GUARDRAIL_BLOCKED | reason=infrastructure_leak | pattern='{}' | session={}",
                        pattern, sessionId);
                return GuardrailResult.block("infrastructure_leak");
            }
        }

        // 5. Stack trace — model echoed an internal Java exception
        for (String pattern : STACK_TRACE_PATTERNS) {
            if (lower.contains(pattern)) {
                log.warn("OUTPUT_GUARDRAIL_BLOCKED | reason=stack_trace_leak | pattern='{}' | session={}",
                        pattern, sessionId);
                return GuardrailResult.block("stack_trace_leak");
            }
        }

        // 6. PII redaction — replace sensitive patterns but keep the rest of the response
        String sanitised = response;
        boolean redacted = false;

        Matcher ccMatcher = CREDIT_CARD.matcher(sanitised);
        if (ccMatcher.find()) {
            sanitised = ccMatcher.replaceAll("[REDACTED]");
            redacted = true;
            log.warn("OUTPUT_GUARDRAIL_REDACTED | reason=credit_card | session={}", sessionId);
        }

        Matcher ssnMatcher = SSN.matcher(sanitised);
        if (ssnMatcher.find()) {
            sanitised = ssnMatcher.replaceAll("[REDACTED]");
            redacted = true;
            log.warn("OUTPUT_GUARDRAIL_REDACTED | reason=ssn | session={}", sessionId);
        }

        Matcher pwdMatcher = PASSWORD_KV.matcher(sanitised);
        if (pwdMatcher.find()) {
            sanitised = pwdMatcher.replaceAll("$1=[REDACTED]");
            redacted = true;
            log.warn("OUTPUT_GUARDRAIL_REDACTED | reason=password_kv | session={}", sessionId);
        }

        if (redacted) {
            return GuardrailResult.redact(sanitised);
        }

        return GuardrailResult.pass(response);
    }

    // ─── Result type ──────────────────────────────────────────────────────────

    public enum ResultType { PASS, REDACT, BLOCK }

    public record GuardrailResult(ResultType type, String content, String reason) {

        public static GuardrailResult pass(String content) {
            return new GuardrailResult(ResultType.PASS, content, null);
        }

        public static GuardrailResult redact(String content) {
            return new GuardrailResult(ResultType.REDACT, content, "redacted");
        }

        public static GuardrailResult block(String reason) {
            return new GuardrailResult(ResultType.BLOCK, BLOCKED_RESPONSE, reason);
        }

        public boolean isBlocked() { return type == ResultType.BLOCK; }
        public boolean isRedacted() { return type == ResultType.REDACT; }
    }
}
