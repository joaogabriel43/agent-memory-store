# CLAUDE.md — Agent Memory Store

> **Read this file first in every session.** It contains the project stack, architecture rules,
> and Architectural Decision Records (ADRs) that govern all implementation choices.

---

## Stack

| Layer        | Technology                              |
|-------------|------------------------------------------|
| Language     | Java 21                                 |
| Framework    | Spring Boot 3.2                         |
| Database     | PostgreSQL 16 + pgvector                |
| Migrations   | Flyway                                  |
| Build        | Maven                                   |
| AI           | Spring AI (OpenAI embeddings)           |
| Observability| Spring Boot Actuator, p6spy             |
| Testing      | JUnit 5, Testcontainers, jqwik (PBT)   |
| CI/CD        | GitHub Actions (OWASP, Trivy, GitLeaks, SpotBugs) |

---

## Architecture

The project follows **Clean Architecture** with strict package boundaries:

```
com.agentmemorystore
├── domain          # Entities, value objects, domain contracts (no framework deps)
├── application     # Use cases, application services, port interfaces
├── infrastructure  # Database adapters, external API clients, Spring configs
└── presentation    # REST controllers, DTOs, request/response mapping
```

### Rules

1. **Dependency direction**: `presentation → application → domain ← infrastructure`.
2. **Domain** has zero framework imports — pure Java only.
3. **Infrastructure** implements ports defined in `application`.
4. **No logic leaks**: controllers must only call application services; never access domain directly.

---

## Coding Standards

- **Language**: All code, variables, comments, and commit messages in **English**.
- **Commits**: Semantic commits (`feat:`, `fix:`, `chore:`, `docs:`, `ci:`, `refactor:`, `test:`).
- **Principles**: SOLID, DRY, KISS, YAGNI (strict — do not over-engineer).

---

## ADRs (Architectural Decision Records)

### ADR-001: Single `memories` Table with Discriminator

**Status**: Accepted

**Context**: The system needs to store different types of memories (episodic, semantic, procedural)
for AI agents.

**Decision**: Use a single `memories` table with a `memory_type` column (VARCHAR) acting as a
discriminator. Valid values: `EPISODIC`, `SEMANTIC`, `PROCEDURAL`.

**Rationale**: Avoids premature table proliferation. A single table with a discriminator simplifies
queries, migrations, and the domain model while maintaining clear type separation.

**Consequences**: All memory types share the same schema. Type-specific fields (if needed later)
would require nullable columns or a JSON column.

---

### ADR-002: Hybrid Search (Semantic Weight + Recency)

**Status**: Accepted

**Context**: Pure semantic search ignores temporal relevance; pure recency search ignores meaning.

**Decision**: Implement hybrid search combining semantic similarity (cosine distance via pgvector)
and recency (time decay). The weights for each factor are configurable via application properties.

**Rationale**: Allows tuning the balance between meaning and freshness without code changes.
Properties like `memory.search.semantic-weight` and `memory.search.recency-weight` will control
the scoring formula.

**Consequences**: The search query becomes more complex but significantly more useful. Weight
tuning requires experimentation per use case.

---

### ADR-003: Memory Decay via `last_accessed_at`

**Status**: Accepted

**Context**: Unused memories should gradually lose relevance, mimicking natural memory decay.

**Decision**: Calculate memory decay based on the `last_accessed_at` timestamp. Every read
operation updates this field, effectively "refreshing" the memory.

**Rationale**: Simple, database-native approach that avoids background jobs for decay calculation.
The decay factor is computed at query time using the time delta from `last_accessed_at`.

**Consequences**: Read operations have a side effect (updating `last_accessed_at`). This is
acceptable as it's a lightweight UPDATE and aligns with the "access refreshes memory" metaphor.

---

### ADR-004: Use Spring AI `EmbeddingModel` Abstraction

**Status**: Accepted

**Context**: The system needs to generate vector embeddings for memory content.

**Decision**: Always use the `EmbeddingModel` interface from Spring AI. Never call OpenAI APIs
directly.

**Rationale**: Decouples the application from any specific embedding provider. Switching from
OpenAI to another provider (Ollama, Cohere, etc.) requires only a configuration change, not
code changes.

**Consequences**: Slight abstraction overhead. Must ensure the chosen Spring AI starter is
compatible with the desired provider.

---

### ADR-005: Spring Batch for Episodic → Semantic Consolidation

**Status**: Accepted

**Context**: Over time, episodic memories should be consolidated into higher-level semantic
memories (similar to how human memory works).

**Decision**: Use Spring Batch to implement an idempotent job that processes episodic memories
and generates consolidated semantic memories.

**Rationale**: Spring Batch provides built-in job management, restartability, chunk processing,
and idempotency guarantees. The `source_memory_ids` column in the `memories` table tracks which
episodic memories were consolidated into each semantic memory.

**Consequences**: Adds Spring Batch as a dependency (future sprint). Job scheduling and
monitoring need to be configured.

---

### ADR-006: Multitenancy via `X-Tenant-Id` Header

**Status**: Accepted

**Context**: The API must support multiple tenants (AI agents or applications) with data isolation.

**Decision**: Implement logical multitenancy using an `X-Tenant-Id` HTTP header. All queries are
filtered by `tenant_id`. No JWT authentication in the MVP.

**Rationale**: Simplest approach for MVP. Header-based identification avoids the complexity of
a full auth system while still providing data isolation. JWT can be layered on later.

**Consequences**: No authentication — any client can impersonate any tenant. Acceptable for MVP
but must be replaced with proper auth before production.

---

### ADR-007: Memory Types in MVP

**Status**: Accepted

**Context**: The `memory_type` discriminator defines three types, but not all need implementation
in the MVP.

**Decision**:
- `EPISODIC`: Created via API (user-facing endpoint). Primary type in MVP.
- `SEMANTIC`: Generated by the Spring Batch consolidation job (ADR-005). Not directly created via API.
- `PROCEDURAL`: Defined in schema but **not implemented** in the MVP.

**Rationale**: Focuses MVP scope on the core episodic → semantic pipeline. Procedural memories
(how-to knowledge) require a different ingestion mechanism that can be designed later.

**Consequences**: `PROCEDURAL` exists in the database constraint but has no application code path.
API validation should reject direct creation of `SEMANTIC` and `PROCEDURAL` types.
