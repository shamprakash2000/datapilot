# gemini-chat — AI Learning Project

Spring Boot app that calls Google Gemini AI via REST API. Built for learning AI backend development.

## Setup

1. Get a free API key from https://aistudio.google.com
2. Paste it in `application.properties` as `gemini.api.key=YOUR_KEY`
3. Run: `mvn spring-boot:run`

## Endpoints

| Method | URL | Body | Description |
|---|---|---|---|
| GET | `/api/health` | — | Check app is running |
| POST | `/api/chat` | `{"message": "your question"}` | Ask Gemini anything |

## Model

`gemini-3.5-flash-lite` — Free tier: 15 req/min, 500 req/day

## Learning Progress

### Phase 1 — Foundation ✅ Done
- [x] Spring Boot project setup
- [x] Connect to Gemini API (plain HTTP — no Spring AI, corporate proxy limitation)
- [x] First successful API call
- [x] Parse JSON response, return clean answer
- [x] Understand tokens, rate limits, request/response format
- [x] Conversation history (AI remembers previous messages)
- [x] System prompt (tell AI what role to play)

### Phase 2 — RAG
- [ ] Embeddings
- [ ] pgvector setup
- [ ] Store and search documents
- [ ] Full RAG pipeline

### Phase 3 — Agents & Tool Use
- [ ] ReAct loop
- [ ] @Tool annotation / function calling
- [ ] Agent memory

### Phase 4 — MCP
- [ ] MCP server
- [ ] MCP client
- [ ] Connect tools via protocol

### Phase 5 — Multi-Agents
- [ ] Orchestrator + Worker pattern
- [ ] Agent communication

### Phase 6 — Observability
- [ ] Langfuse setup
- [ ] Tracing AI calls

## Why plain HTTP instead of Spring AI?

`spring-ai-google-genai-spring-boot-starter` is not available in the corporate Maven proxy (gbt-maven-central).
Using Java's built-in `HttpClient` to call Gemini REST API directly — same result, more transparent.
