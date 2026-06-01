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

---

## ⚙️ Environment & Build Notes

- **Tests require Docker** (Testcontainers spins up `pgvector/pgvector:pg16`) and WireMock. They will
  not run without a Docker engine available.
- **`spring.ai.retry.max-attempts: 1` is set in the test profile** (`application-test.yml`). Without
  it, a failing embedding/chat call retries up to 10 times with exponential backoff (up to minutes),
  which hangs the suite once the circuit breaker no longer opens on the first failures. One attempt
  also makes the Spring Batch retry/skip scenario deterministic (exactly one HTTP call per attempt).
- **CI currently runs security scans only** (OWASP, Trivy, GitLeaks, SpotBugs) — it does **not** run
  `mvn test`. This is why the integration suite was red without anyone noticing during Sprints 0–3.
  **Recommended: add `mvn verify` (or at least `mvn test`) to the pipeline.**

---

## 🐛 Known Pitfalls & How to Avoid Them

### 2026-06-01 — Spring Batch 5 sequence is `BATCH_JOB_SEQ`, not `BATCH_JOB_INSTANCE_SEQ`
**What happened**: The consolidation job failed at launch with `relation "batch_job_seq" does not exist`.
**Why**: Flyway `V3` created `BATCH_JOB_INSTANCE_SEQ` (the Batch 4 name); Spring Batch 5's
`JdbcJobInstanceDao` reads the job-instance id from `BATCH_JOB_SEQ`.
**How to prevent**: When hand-writing the Batch schema, copy it from the Spring Batch 5
`schema-postgresql.sql` for the exact Batch version on the classpath. The three sequences are
`BATCH_STEP_EXECUTION_SEQ`, `BATCH_JOB_EXECUTION_SEQ`, `BATCH_JOB_SEQ`.

### 2026-06-01 — Bean Validation silently ignored without `spring-boot-starter-validation`
**What happened**: `@Valid @NotBlank` on request bodies did nothing; blank `content` returned 201.
**Why**: `spring-boot-starter-web` does **not** bring Hibernate Validator since Boot 2.3.
**How to prevent**: Add `spring-boot-starter-validation` whenever relying on `@Valid`/`@Validated`.

### 2026-06-01 — Two `JobLauncher` beans → wrong one injected
**What happened**: The intended async REST behavior was inactive; `JobLauncherTestUtils` got a null launcher.
**Why**: Spring Boot auto-configures a synchronous `jobLauncher`; our custom async bean was a second
candidate, and the controller bound to Boot's by parameter name.
**How to prevent**: Mark the intended launcher `@Primary`; in tests, set a synchronous launcher on
`JobLauncherTestUtils` explicitly.

### 2026-06-01 — Soft delete must filter `deleted_at IS NULL` and report affected rows
**What happened**: `DELETE` returned 204 for non-existent / cross-tenant / already-deleted memories.
**How to prevent**: Mutating queries that back a 404 contract must return the affected-row count, and
soft-delete updates must include `AND deleted_at IS NULL` so a second delete is a clean 404.

### 2026-06-01 — Locale-sensitive `String.format` breaks JSON test fixtures
**What happened**: Embedding stub JSON used `String.format("%.8f", ...)` → on pt-BR produced `,` →
invalid JSON → Jackson "Leading zeroes not allowed" → 503 cascade.
**How to prevent**: Always pass `Locale.US` (or `Locale.ROOT`) when formatting numbers into JSON/SQL.

### 2026-06-01 — Spring AI M1 NPEs on incomplete OpenAI responses
**What happened**: Chat stubs missing `finish_reason` and embedding stubs missing `usage` caused
`NullPointerException` in the Spring AI parser, masked by the circuit-breaker fallback as
"service unavailable", so the batch silently skipped every tenant.
**How to prevent**: WireMock stubs for Spring AI must mirror the full OpenAI payload shape
(`finish_reason`, `usage`, `object`, `index`, `model`).

---

## 📝 CLAUDE.md Changelog

- **2026-06-01**: Added Environment & Build Notes, Known Pitfalls, and this changelog following the
  final architectural review of Sprints 0–3 (PR #1). No ADRs changed.
