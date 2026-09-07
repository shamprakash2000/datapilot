package com.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

@Component
public class DatabaseTools {

    private static final Logger log = LoggerFactory.getLogger(DatabaseTools.class);

    private static final Set<String> ALLOWED_TABLES = Set.of("da_products", "da_orders");

    private static final ThreadLocal<Consumer<String>> STATUS_EMITTER = new ThreadLocal<>();

    public static void setStatusEmitter(Consumer<String> emitter) { STATUS_EMITTER.set(emitter); }
    public static void clearStatusEmitter() { STATUS_EMITTER.remove(); }
    private static void emitStatus(String message) {
        Consumer<String> emitter = STATUS_EMITTER.get();
        if (emitter != null) emitter.accept(message);
    }

    private final JdbcTemplate jdbcTemplate;

    public DatabaseTools(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Tool(description = "List all tables the agent is allowed to query. Call this first if the user has not named a specific table.")
    public String listTables() {
        emitStatus("Listing available tables...");
        return "Available tables: da_products, da_orders";
    }

    @Tool(description = "Get the schema (column names and types) plus 3 sample rows of a table. Always call this before writing a query so you know the exact column names.")
    public String getTableSchema(String tableName) {
        if (!ALLOWED_TABLES.contains(tableName.toLowerCase())) {
            return "Error: table '" + tableName + "' is not in the allowed list. Use listTables to see available tables.";
        }
        emitStatus("Getting schema for table: " + tableName + "...");
        try {
            List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                    "SELECT column_name, data_type FROM information_schema.columns " +
                    "WHERE table_name = ? ORDER BY ordinal_position",
                    tableName.toLowerCase()
            );

            StringBuilder sb = new StringBuilder();
            sb.append("Table: ").append(tableName).append("\nColumns:\n");
            for (Map<String, Object> col : columns) {
                sb.append("  - ").append(col.get("column_name"))
                  .append(" (").append(col.get("data_type")).append(")\n");
            }

            List<Map<String, Object>> samples = jdbcTemplate.queryForList(
                    "SELECT * FROM " + tableName + " LIMIT 3"
            );
            sb.append("\nSample rows:\n");
            if (samples.isEmpty()) {
                sb.append("  (no rows yet)\n");
            } else {
                for (Map<String, Object> row : samples) {
                    sb.append("  ").append(row).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("getTableSchema error for {}: {}", tableName, e.getMessage());
            return "Error getting schema for '" + tableName + "': " + e.getMessage();
        }
    }

    @Tool(description = "Execute a SELECT query against the database and return the results as a formatted table. Only SELECT is allowed; results are capped at 20 rows.")
    public String executeQuery(String sql) {
        emitStatus("Executing query...");

        String trimmed = sql.trim();

        // Must start with SELECT
        if (!trimmed.toUpperCase().startsWith("SELECT")) {
            return "Error: only SELECT queries are allowed.";
        }

        String lower = trimmed.toLowerCase();

        // Block dangerous DML/DDL keywords anywhere in the statement
        for (String kw : List.of("insert ", "update ", "delete ", "drop ", "alter ", "truncate ", "create ", "grant ", "revoke ")) {
            if (lower.contains(kw)) {
                return "Error: keyword '" + kw.trim() + "' is not allowed.";
            }
        }

        // Block access to system / non-agent tables by name
        for (String blocked : List.of("chat_history", "spring_ai_chat_memory", "pg_", "pg_catalog")) {
            if (lower.contains(blocked)) {
                return "Error: access to system table '" + blocked + "' is not allowed.";
            }
        }

        // Append LIMIT 20 if the query has no limit clause
        String finalSql = lower.contains("limit") ? trimmed : trimmed + " LIMIT 20";

        log.info("DB agent executing: {}", finalSql);
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(finalSql);
            if (rows.isEmpty()) {
                return "Query returned no rows.";
            }

            List<String> headers = List.copyOf(rows.get(0).keySet());
            StringBuilder sb = new StringBuilder();
            sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
            sb.append("| ").append("--- | ".repeat(headers.size())).append("\n");
            for (Map<String, Object> row : rows) {
                sb.append("| ");
                for (String h : headers) {
                    Object val = row.get(h);
                    sb.append(val != null ? val.toString() : "NULL").append(" | ");
                }
                sb.append("\n");
            }
            sb.append("\n(").append(rows.size()).append(" row(s))");
            return sb.toString();
        } catch (Exception e) {
            log.error("executeQuery error: {}", e.getMessage());
            return "Error executing query: " + e.getMessage();
        }
    }
}
