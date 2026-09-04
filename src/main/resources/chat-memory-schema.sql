CREATE TABLE IF NOT EXISTS chat_history (
    conversation_id VARCHAR(256) NOT NULL,
    content         TEXT         NOT NULL,
    type            VARCHAR(64)  NOT NULL,
    timestamp       TIMESTAMP    NOT NULL
);
