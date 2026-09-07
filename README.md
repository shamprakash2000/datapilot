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

### Shared Session — `/api/session`

| Method | URL | Description |
|---|---|---|
| POST | `/api/session` | Create a new session, returns `conversationId` — works with any agent |

### Spring AI Chat with Memory — `/api/chat-ai`

| Method | URL | Body | Description |
|---|---|---|---|
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

### Travel Agent — `/api/agent`

A fully agentic travel planner for Indian destinations. The LLM autonomously decides which tools to call, calls them (in parallel where possible), and synthesises results into a response.

**Tools available to the agent:**

| Tool | Description |
|---|---|
| `getWeather` | Live weather + seasonal context via OpenWeatherMap |
| `getAttractions` | Top sights, food, and activities for the city |
| `estimateBudget` | Per-day cost breakdown (hotel, food, transport, activities) |
| `findHotels` | Hotel options across budget / mid-range / luxury |
| `getModeOfTransport` | Train, bus, flight, and road options between cities |
| `searchFlights` | Estimated flight options and prices |
| `getCurrentDateTime` | Current date/time for trip date calculations |

**Endpoints:**

| Method | URL | Body | Description |
|---|---|---|---|
| POST | `/api/agent/chat` | `{"conversationId": "uuid", "message": "Plan a trip to Goa"}` | Conversational agent with memory and tools. Timeout: 45s |
| POST | `/api/agent/chat/stream` | `{"conversationId": "uuid", "message": "..."}` | Same agent, streaming SSE — live tool status events + final response |
| POST | `/api/agent/itinerary` | `{"destination": "Goa", "days": "3", "month": "October", "fromCity": "Bangalore"}` | Structured JSON itinerary (day plans, budget, hotels, transport). India only. Timeout: 90s |
| DELETE | `/api/agent/session/{conversationId}` | — | Clear conversation history |

**Streaming SSE event types (`/api/agent/chat/stream`):**

```
event: status   →  fired in real-time as each tool executes
data: Checking weather in Goa for October...

event: status
data: Finding top attractions in Goa...

event: token    →  complete agent response, after all tools finish
data: Here is your 3-day Goa itinerary...

data: [DONE]    →  stream ended
```

Test with curl to see live events:
```bash
curl -X POST http://localhost:8080/api/agent/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"conversationId":"YOUR_SESSION_ID","message":"Plan a 3-day trip to Goa in October"}' \
  --no-buffer
```

### Database Agent — `/api/db-agent`

A natural language database query agent. Ask questions in plain English — the LLM runs a ReAct loop: lists tables, inspects schemas, generates safe SELECT queries, and returns formatted results.

**Tables the agent can query:**

| Table | Columns |
|---|---|
| `da_products` | id, name, category, price, stock, added_on |
| `da_orders` | id, product_id, customer_name, city, quantity, total_amount, order_date, status |

The tables are pre-loaded with 20 products (8 categories, Indian pricing) and 80 orders (15 Indian cities, 4 statuses, dates spanning June–September 2026).

**Endpoints:**

| Method | URL | Body | Description |
|---|---|---|---|
| POST | `/api/db-agent/chat` | `{"conversationId": "uuid", "message": "Show top 5 products by revenue"}` | Natural language query, blocking. Timeout: 30s |
| POST | `/api/db-agent/chat/stream` | `{"conversationId": "uuid", "message": "..."}` | Same agent, SSE streaming — live tool status + final response |

**Example questions:**
- "Which city has the most orders?"
- "Show me the top 3 products by total revenue"
- "How many orders are in SHIPPED status?"
- "What is the average order value for delivered orders?"
- "List all orders from Bangalore placed this month"

**Safety enforced by `DatabaseTools`:**
- Only `SELECT` statements allowed — `INSERT`, `UPDATE`, `DELETE`, `DROP` all rejected
- Only `da_products` and `da_orders` accessible — `chat_history` and system tables blocked
- Results capped at 20 rows automatically if no `LIMIT` in the query

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

**Three separate ChatClients for the agent** — a single `ChatClient.Builder` accumulates `defaultTools()` calls across builds. Using `ChatModel` directly to build each client independently prevents duplicate tool registration. Each client has a distinct role: `chatClient` (memory + tools), `itineraryClient` (no memory, structured JSON output), `validationClient` (no tools, YES/NO only).

**India-only validation with a pre-check LLM call** — `.entity(Class)` forces a JSON schema response, overriding any "decline" instruction in the system prompt. The itinerary endpoint uses a separate lightweight `validationClient` call first that returns YES/NO before invoking the main itinerary agent.

**Hybrid streaming (Pattern 1)** — Spring AI 1.1.8 strips `thought_signature` from tool calls in multi-round streaming, causing Gemini to reject round 2 with 400. The streaming endpoint runs the agent blocking on a `boundedElastic` thread, streams live `status` SSE events via a `ThreadLocal<Consumer<String>>` in `AgentTools`, then emits the final response as a `token` event — matching how ChatGPT/Claude show "Searching..." indicators.

**HikariCP tuning for Neon serverless** — Neon closes idle connections after 5 minutes. Without tuning, HikariCP holds dead references and wastes 5–20s reconnecting. Fix: `max-lifetime=240000`, `keepalive-time=60000`, `minimum-idle=1`.

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

### Phase 3 — Agents & Tool Use ✅
- ReAct loop — LLM reasons → calls tools → reasons again → final answer
- 7 tools: weather (live API), attractions, budget, hotels, transport, flights, datetime
- Parallel tool calling — system prompt instructs Gemini to call independent tools simultaneously
- Error handling — tools return error strings instead of throwing, agent recovers gracefully
- Timeout — `CompletableFuture.orTimeout` with 45s (chat) and 90s (itinerary), returns 408
- Structured output — `/api/agent/itinerary` returns typed JSON via Spring AI `.entity()`
- India-only validation — pre-check LLM call prevents structured output from bypassing restrictions
- Hybrid streaming SSE — live tool status events via `ThreadLocal`, final response as `token` event
- HikariCP pool tuning for Neon serverless connection lifecycle

### Phase 4 — Database Agent ✅
- LLM generates real SQL from natural language via `@Tool` methods
- Schema introspection — agent discovers column names at runtime via `getTableSchema`
- Safety enforcement — SELECT-only, table whitelist, LIMIT 20 injection, DML/DDL keyword blocking
- Same ReAct loop + ThreadLocal streaming pattern as Phase 3 (Travel Agent)
- 100-row seed dataset (20 products + 80 orders) auto-loaded via `spring.sql.init.data-locations`

### Phase 5 — MCP
- MCP server and client
- Connect tools via protocol

### Phase 5 — Multi-Agents
- Orchestrator + Worker pattern
- Agent communication

### Phase 6 — Observability
- Tracing AI calls
- Metrics and dashboards
