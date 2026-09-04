# gemini-chat — AI Backend Learning Project

Spring Boot app exploring AI backend development phase by phase — plain HTTP to Spring AI, RAG pipelines, vector search, persistent memory, and cloud deployment.

**Live:** https://gemini-chat-a7lr.onrender.com  
**API Docs (Swagger):** https://gemini-chat-a7lr.onrender.com/swagger-ui.html

---

## Tech Stack

| Layer | Technology |
|---|---|
| Runtime | Java 17, Spring Boot 3.4.1 |
| AI Model | Google Gemini (`gemini-3.5-flash-lite`, `gemini-embedding-001`) |
| AI Framework | Spring AI 1.1.8 |
| Vector DB | Pinecone (768-dim, cosine similarity) |
| Memory DB | PostgreSQL on Neon (chat history) |
| Deployment | Render (Docker, auto-deploy on push) |

---

## Local Setup

### Prerequisites
- Java 17+
- Maven 3.9+
- API keys: Gemini, Pinecone, Neon PostgreSQL

### Environment Variables

```bash
GEMINI_API_KEY=your_gemini_key
PINECONE_API_KEY=your_pinecone_key
PINECONE_HOST=your_pinecone_host_url
NEON_HOST=your_neon_host
NEON_DB=your_db_name
NEON_USER=your_db_user
NEON_PASSWORD=your_db_password
```

### Run

```bash
mvn spring-boot:run
```

App starts at `http://localhost:8080`  
Swagger UI at `http://localhost:8080/swagger-ui.html`

---

## API Endpoints

### Plain HTTP Chat — `/api/chat`

| Method | URL | Body | Description |
|---|---|---|---|
| POST | `/api/chat` | `{"message": "your question"}` | Single-turn chat with Gemini |

### Spring AI Chat with Memory — `/api/chat-ai`

| Method | URL | Body | Description |
|---|---|---|---|
| POST | `/api/chat-ai/session` | — | Start a new conversation, returns `conversationId` |
| POST | `/api/chat-ai` | `{"conversationId": "uuid", "message": "hi"}` | Chat with persistent memory (last 20 messages) |
| DELETE | `/api/chat-ai/session/{conversationId}` | — | Clear conversation history |

### Plain HTTP RAG — `/api/rag`

| Method | URL | Body | Description |
|---|---|---|---|
| POST | `/api/rag/ingest` | `{"text": "document content"}` | Chunk → embed → store in Pinecone |
| POST | `/api/rag/ask` | `{"question": "your question"}` | Semantic search → Gemini answer |

### Spring AI RAG — `/api/rag-ai`

| Method | URL | Body | Description |
|---|---|---|---|
| POST | `/api/rag-ai/ingest` | `{"text": "document content"}` | Chunk → embed → store via Spring AI VectorStore |
| POST | `/api/rag-ai/ask` | `{"question": "your question"}` | RAG answer, falls back to LLM if no context found |

**`/api/rag-ai/ask` response includes a `source` field:**
- `"source": "rag"` — answer grounded in your ingested documents
- `"source": "llm-fallback"` — no relevant context found, Gemini answered from general knowledge

### Health

| Method | URL | Description |
|---|---|---|
| GET | `/actuator/health` | Returns `{"status":"UP"}` — used by Render |

---

## Key Design Decisions

**Two parallel RAG pipelines** — plain HTTP and Spring AI both exist intentionally. The plain HTTP pipeline shows what Spring AI abstracts away under the hood.

**Pinecone namespaces** — plain HTTP RAG uses namespace `""` (default), Spring AI RAG uses namespace `spring-ai`. They never share data — different pipelines have different metadata schemas and mixing them causes crashes.

**LLM fallback in Spring AI RAG** — when Pinecone finds no relevant context (similarity < 0.70), the ask endpoint falls back to a direct Gemini call instead of returning a dead-end message. The `source` field in the response tells you which path was taken.

**768-dim embeddings** — `gemini-embedding-001` produces 3072-dim vectors by default. We truncate to 768 using MRL (Matryoshka Representation Learning) — the first N dimensions are semantically complete on their own, so quality is preserved while Pinecone storage cost is reduced.

**SCRAM-SHA-256 channel binding** — corporate networks run SSL-intercepting proxies that break PostgreSQL's SCRAM-SHA-256-PLUS authentication (which binds auth to the TLS channel). Fix: `channelBinding=disable` via HikariCP data-source-properties forces the standard SCRAM-SHA-256 instead.

---

## Learning Progress

### Phase 1 — Foundation ✅
- Spring Boot + plain HTTP to Gemini REST API
- Parse JSON response, understand tokens and rate limits
- Conversation history with system prompts

### Phase 2 — RAG + Spring AI ✅
- Embeddings: text → 768-dim vectors via `gemini-embedding-001`
- Pinecone vector DB: upsert, semantic search, score threshold (0.70)
- Sentence-aware chunking with overlap (100-word chunks, 20-word overlap)
- Batch embedding and batch upsert (N chunks in 1 API call each)
- Deterministic chunk IDs (SHA-256) — re-ingest overwrites, no duplicates
- Spring AI: `ChatClient`, `EmbeddingModel`, `VectorStore`, `JdbcChatMemoryRepository`
- Persistent chat memory in PostgreSQL (Neon), scoped per `conversationId`
- LLM fallback when vector search returns no results
- Deployed to Render with Docker (auto-deploy on `git push`)
- Health check via Spring Boot Actuator
- Input size guard (50k character limit on ingest)

### Phase 3 — Agents & Tool Use 🔜
- ReAct loop (Reason + Act)
- Function calling / tool use with Gemini
- Agent decides which tool to call and when

### Phase 4 — MCP
- MCP server and client
- Connect tools via protocol

### Phase 5 — Multi-Agents
- Orchestrator + Worker pattern
- Agent communication

### Phase 6 — Observability
- Tracing AI calls
- Metrics and dashboards
