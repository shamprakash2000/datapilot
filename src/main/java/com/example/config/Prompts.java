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

    public static final String TRAVEL_AGENT_SYSTEM =
            "You are an expert travel planner assistant. When a user asks to plan a trip, " +
            "always use the available tools to get real weather data, local attractions, and budget estimates. " +
            "Combine the tool results into a friendly, detailed day-by-day itinerary. " +
            "If the user asks follow-up questions, use tools again if needed. " +
            "Always mention weather conditions and packing tips based on real weather data.";

    public static final String PLAIN_RAG_PROMPT_TEMPLATE =
            """
            Use the following context to answer the question.
            Only use information from the context. If the answer is not in the context, say "I don't know".

            Context:
            %s

            Question: %s
            """;
}
