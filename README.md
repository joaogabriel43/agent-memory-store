# Agent Memory Store

REST API providing **long-term memory for AI agents** using semantic search and pgvector.

Built with a Clean Architecture approach, this service allows AI agents to store, retrieve, and
consolidate memories using vector embeddings for semantic similarity search.

---

## Tech Stack

| Component     | Technology                            |
|--------------|----------------------------------------|
| Language      | Java 21                               |
| Framework     | Spring Boot 3.2                       |
| Database      | PostgreSQL 16 + pgvector              |
| Migrations    | Flyway                                |
| Build         | Maven                                 |
| AI Embeddings | Spring AI (OpenAI)                    |
| SQL Tracing   | p6spy                                 |
| Testing       | JUnit 5, Testcontainers, jqwik        |
| CI/CD         | GitHub Actions                        |

---

## Prerequisites

- **Java 21** (JDK)
- **Maven 3.9+**
- **Docker** and **Docker Compose**

---

## Getting Started

### 1. Start the database

```bash
docker-compose up -d
```

This starts PostgreSQL 16 with the pgvector extension on port `5432`.

### 2. Build the project

```bash
mvn clean install
```

### 3. Run the application

```bash
mvn spring-boot:run
```

The application will:
- Connect to PostgreSQL via p6spy (SQL tracing enabled)
- Run Flyway migrations automatically
- Expose Actuator endpoints at `/actuator/health`

### 4. Verify

```bash
curl http://localhost:8080/actuator/health
```

---

## Project Structure

```
src/main/java/com/agentmemorystore/
├── domain/           # Core entities, value objects, domain contracts
├── application/      # Use cases, services, port interfaces
├── infrastructure/   # Database adapters, external APIs, configs
└── presentation/     # REST controllers, DTOs
```

See [CLAUDE.md](CLAUDE.md) for architectural decisions and ADRs.

---

## Environment Variables

| Variable        | Description              | Default          |
|----------------|--------------------------|------------------|
| `OPENAI_API_KEY`| OpenAI API key for embeddings | `sk-placeholder` |

---

## CI/CD

The project includes a GitHub Actions security pipeline (`.github/workflows/ci.yml`) with:

- **SpotBugs** — Static analysis for bug patterns
- **OWASP Dependency-Check** — Known vulnerability scanning (fails on CVSS ≥ 7)
- **Trivy** — Filesystem vulnerability scan (CRITICAL severity)
- **Gitleaks** — Secret detection across git history

---

## License

This project is proprietary. All rights reserved.
