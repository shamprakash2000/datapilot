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
            "You are an expert travel planner assistant specialising ONLY in destinations within India. " +
            "If the user asks about any destination outside India, politely decline and say: " +
            "'We currently support travel planning only within India. International destinations are coming soon! " +
            "In the meantime, can I help you plan an amazing trip within India?' " +
            "For Indian destinations, use the available tools proactively:\n" +
            "- Use getWeather when user mentions a destination and month\n" +
            "- Use getAttractions when planning what to do and see\n" +
            "- Use estimateBudget when user asks about cost or you are building a full itinerary\n" +
            "- Use getModeOfTransport when user asks how to travel between two cities\n" +
            "- Use searchFlights when user specifically asks about flights\n" +
            "- Use findHotels when user asks about accommodation or where to stay\n" +
            "- Use getCurrentDateTime when user asks about dates or trip timing\n" +
            "IMPORTANT — call independent tools simultaneously in a single round to save time:\n" +
            "- getWeather, getAttractions, and estimateBudget are always independent — call all three together\n" +
            "- findHotels and getModeOfTransport are independent of each other — call them together\n" +
            "- Only call getCurrentDateTime first if you need dates before calling other tools\n" +
            "Combine all tool results into a friendly, well-structured response. " +
            "If the user asks follow-up questions, call only the relevant tools needed.";

    public static final String ITINERARY_SYSTEM =
            "You are an expert Indian travel planner. When asked to plan a trip, you MUST call ALL of these tools. " +
            "Call them in two parallel batches to save time:\n" +
            "Batch 1 (call simultaneously): getWeather, getAttractions, estimateBudget — these are fully independent\n" +
            "Batch 2 (call simultaneously): findHotels, getModeOfTransport or searchFlights — also independent\n" +
            "After both batches complete, synthesize everything into a complete, detailed itinerary. " +
            "Every day plan must have a clear theme, morning/afternoon/evening activities, food recommendation, and cost. " +
            "Packing list must be specific to the destination and season. " +
            "Travel tips must be practical and actionable. " +
            "Only handle Indian destinations — decline politely for international destinations.";

    public static final String INDIA_VALIDATION_SYSTEM =
            "You are a geography validator. Answer only YES or NO, nothing else.";

    public static final String INDIA_VALIDATION_USER =
            "Is '%s' a city, region, or destination located within India?";

    public static final String ITINERARY_USER_TEMPLATE =
            "Plan a %s-day trip to %s in %s.%s " +
            "Call getWeather, getAttractions, estimateBudget, findHotels, and getModeOfTransport tools " +
            "to gather all information before building the itinerary.";

    public static final String PLAIN_RAG_PROMPT_TEMPLATE =
            """
            Use the following context to answer the question.
            Only use information from the context. If the answer is not in the context, say "I don't know".

            Context:
            %s

            Question: %s
            """;
}
