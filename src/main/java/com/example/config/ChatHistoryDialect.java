package com.example.config;

import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepositoryDialect;

// Custom dialect that points all queries to our table name (chat_history)
// instead of Spring AI's default (SPRING_AI_CHAT_MEMORY)
public class ChatHistoryDialect implements JdbcChatMemoryRepositoryDialect {

    private static final String TABLE = "chat_history";

    @Override
    public String getSelectMessagesSql() {
        return "SELECT content, type FROM " + TABLE + " WHERE conversation_id = ? ORDER BY timestamp";
    }

    @Override
    public String getInsertMessageSql() {
        return "INSERT INTO " + TABLE + " (conversation_id, content, type, timestamp) VALUES (?, ?, ?, ?)";
    }

    @Override
    public String getSelectConversationIdsSql() {
        return "SELECT DISTINCT conversation_id FROM " + TABLE;
    }

    @Override
    public String getDeleteMessagesSql() {
        return "DELETE FROM " + TABLE + " WHERE conversation_id = ?";
    }
}
