package com.example.config;

public final class Prompts {

    private Prompts() {}

    public static final String RAG_SYSTEM =
            "You are a helpful assistant. Answer ONLY based on the provided context. " +
            "If the context lacks enough information, say so clearly.";

    public static final String RAG_USER_TEMPLATE =
            "Context:\n%s\n\nQuestion: %s";

    public static final String FALLBACK_SYSTEM =
            "You are a helpful assistant. Answer the user's question using your general knowledge.";

    public static final String CHAT_AI_SYSTEM =
            "You are a helpful assistant for a Java backend engineer learning AI development. " +
            "Be concise and practical.";

    public static final String PLAIN_RAG_PROMPT_TEMPLATE =
            """
            Use the following context to answer the question.
            Only use information from the context. If the answer is not in the context, say "I don't know".

            Context:
            %s

            Question: %s
            """;
}
