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
| POST | `/api/chat` | `{"message": "your question"}` | Ask Gemini anything (Phase 1) |
| POST | `/api/rag/ingest` | `{"text": "document content"}` | Chunk, embed, store in Pinecone |
| POST | `/api/rag/ask` | `{"question": "your question"}` | RAG search + Gemini answer |

## Model

`gemini-3.5-flash-lite` — Free tier: 15 req/min, 500 req/day

## Learning Progress

### Phase 1 — Foundation ✅ Done
- [x] Spring Boot project setup
- [x] Connect to Gemini API (plain HTTP — no Spring AI dependency)
- [x] First successful API call
- [x] Parse JSON response, return clean answer
- [x] Understand tokens, rate limits, request/response format
- [x] Conversation history (AI remembers previous messages)
- [x] System prompt (tell AI what role to play)

### Phase 2 — RAG ✅ Done
- [x] Embeddings — text → 768-dim vectors using gemini-embedding-001 (MRL truncation from 3072)
- [x] Pinecone vector DB (cloud) — upsert and semantic search
- [x] Sentence-aware chunking with overlap (100-word chunks, 20-word overlap)
- [x] Batch embedding — N chunks in 1 Gemini API call
- [x] Batch upsert — N vectors in 1 Pinecone API call
- [x] Deterministic chunk IDs (SHA-256 of document) — re-ingest overwrites, no duplicates
- [x] Score threshold (0.70) — filters out semantically distant matches
- [x] Full RAG pipeline: ingest → chunk → embed → store → search → answer
- [x] Metadata storage (text, documentId, chunkIndex, totalChunks) alongside each vector

### Phase 3 — Agents & Tool Use
- [ ] ReAct loop (Reason + Act)
- [ ] Function calling / tool use with Gemini
- [ ] Agent decides which tool to call and when

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

Using Java's built-in `HttpClient` to call Gemini REST API directly — same result, more transparent.
