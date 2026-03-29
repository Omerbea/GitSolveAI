# GitSolve AI

Autonomous Java open-source issue fixer. Scouts GitHub for active Java repositories with `good-first-issue` tickets, triages them by complexity, generates code fixes using LangChain4j AI agents, validates fixes against repository governance rules, and presents results in a local HTML dashboard.

## Architecture

```
Scout Agent → Triage Agent → SWE Agent → Reviewer Agent → Reporter Agent
                                    ↑                ↓
                             Fix Loop Orchestrator (@Scheduled)
                                    ↓
                        PostgreSQL (IssueStore) + Local Dashboard
```

## Stack

| Component | Technology |
|---|---|
| Runtime | Java 21, Spring Boot 3.4, virtual threads |
| AI | LangChain4j 0.36 (Anthropic Claude / Google Gemini) |
| Database | PostgreSQL 16, Flyway migrations, Spring Data JPA |
| Build sandbox | Docker (docker-java), isolated containers with no network access |
| Observability | OpenTelemetry, Micrometer, Prometheus |
| Testing | JUnit 5, Testcontainers, WireMock |

## Prerequisites

- Java 21 (`java --version`)
- Maven 3.9+ (`mvn --version`)
- Docker (OrbStack recommended on macOS)

## Quick Start

```bash
# 1. Clone and enter project
git clone <repo-url>
cd gitsolve-ai

# 2. Start local Postgres
docker compose up -d postgres

# 3. Set required environment variables
export GITHUB_APP_TOKEN=<your-github-app-installation-token>
export ANTHROPIC_API_KEY=<your-anthropic-api-key>   # if using Anthropic
export GEMINI_API_KEY=<your-gemini-api-key>          # if using Gemini

# 4. Run all tests
mvn clean verify

# 5. Start the application
mvn spring-boot:run
```

The application starts on http://localhost:8080. The dashboard is at http://localhost:8080/dashboard.

## Running Tests

```bash
# Unit tests only (no Docker required)
mvn test -Punit

# Integration tests (requires Docker)
mvn test -Pintegration

# Full suite
mvn clean verify
```

Tests are tagged with `@Tag("unit")` or `@Tag("integration")`.

**macOS / OrbStack**: Testcontainers requires the OrbStack Docker socket. This is pinned in `.mvn/jvm.config` — no additional setup needed.

## Environment Variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `GITHUB_APP_TOKEN` | **Yes** | — | GitHub App installation token |
| `ANTHROPIC_API_KEY` | If provider=anthropic | — | Anthropic API key |
| `GEMINI_API_KEY` | If provider=gemini | — | Google AI Studio key |
| `DATABASE_URL` | No | `jdbc:postgresql://localhost:5432/gitsolve` | PostgreSQL JDBC URL |
| `DATABASE_USER` | No | `gitsolve` | DB username |
| `DATABASE_PASSWORD` | No | `gitsolve` | DB password |
| `DOCKER_HOST` | No | auto-detected | Docker daemon socket |

## Configuration

All configuration is in `src/main/resources/application.yml`. Key settings:

```yaml
gitsolve:
  llm:
    provider: anthropic        # "anthropic" or "gemini"
    model: claude-3-5-sonnet-20241022
    max-tokens-per-run: 500000  # daily token budget
    max-iterations-per-fix: 5
  schedule:
    cron: "0 0 0 * * *"        # daily at midnight UTC
```

## Triggering a Manual Run

Once the application is running:

```bash
curl -X POST http://localhost:8080/api/runs/trigger
```

## Observability

- **Health**: `GET /actuator/health`
- **Prometheus metrics**: `GET /actuator/prometheus`
- **OTel traces**: exported to `http://localhost:4318/v1/traces` (configure with `OTEL_EXPORTER_OTLP_ENDPOINT`)

To run the full observability stack locally:

```bash
docker compose up -d
```

## Project Structure

```
com.gitsolve
├── agent.scout       # GitHub repo discovery
├── agent.triage      # Issue complexity classification
├── agent.swe         # Code fix generation
├── agent.reviewer    # Governance validation
├── agent.reporter    # Fix summary generation
├── config            # GitSolveProperties, AgentConfig
├── docker            # BuildEnvironment (isolated Docker containers)
├── github            # GitHub WebClient + DTOs
├── model             # Pure domain records (no framework dependencies)
├── orchestration     # FixLoopOrchestrator, DailyRunScheduler
├── persistence       # IssueStore facade, JPA entities, Flyway migrations
├── dashboard         # Thymeleaf dashboard controller
└── telemetry         # AgentMetrics (Prometheus + OTel)
```

## GitHub App Setup

The app token must be a GitHub App installation token with only these permissions:
- `contents: read` — to clone and read repository files
- `issues: read` — to list and read issues

**Phase 1 does not submit pull requests.** Do not grant `pull_requests: write`.
