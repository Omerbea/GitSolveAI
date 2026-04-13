# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with this repository.

## Commands

```bash
# Build (skip tests)
mvn package -DskipTests

# Run all unit tests (fast, ~10s, no Docker required)
mvn test -Punit

# Run a single test class
mvn test -Punit -Dtest=FixLoopOrchestratorTest

# Run persistence integration tests (real Postgres via Testcontainers)
mvn test -Ppersistence

# Run Docker sandbox integration tests (~90s)
mvn test -Pintegration

# Full suite
mvn clean verify

# Start application (requires Postgres + env vars below)
docker compose up -d postgres
export GITHUB_APP_TOKEN=ghp_...
export ANTHROPIC_API_KEY=sk-ant-...
mvn spring-boot:run
```

Dashboard: http://localhost:8080/  
Health: http://localhost:8080/actuator/health

## Test structure

Tests are tagged with JUnit 5 `@Tag`:

| Tag | Profile | What runs |
|-----|---------|-----------|
| `unit` | `-Punit` | Pure Mockito, no I/O |
| `persistence` | `-Ppersistence` | IssueStore + RunLog against real Postgres |
| `integration` | `-Pintegration` | DockerBuildEnvironment real container tests |

Every new test class must carry exactly one of these tags. The Surefire plugin filters by tag via `<groups>` in `pom.xml`.

## Architecture

**Autonomous pipeline** — `FixLoopOrchestrator` runs on `@Scheduled` cron (daily midnight UTC) and drives these stages in order:

1. **Scout** (`agent/scout`) — Calls GitHub Search API via LangChain4j tool-use (`ScoutTools`). When `gitsolve.github.target-repos` is set in `application.yml`, the LLM scout is bypassed entirely and those repos are used directly.
2. **Triage** (`agent/triage`) — Classifies each issue as `EASY`/`MEDIUM`. `IssueSanitizer` applies deterministic pre/post-LLM rejection rules before the LLM call.
3. **Analysis** (`agent/analysis`) — Produces a structured investigation report (root cause, affected files, suggested approach). Persisted into `IssueRecord` as a `FixReport`.
4. **Fix Instructions** (`agent/instructions`) — Generates repo-specific coding instructions. Stored separately via `IssueStore.saveFixInstructions`.

**Execution and review are user-initiated** via `POST /issues/{id}/execute` from the dashboard — the cron pipeline stops after analysis.

## AI agent wiring

All LangChain4j `AiService` interfaces are instantiated in `AgentConfig.java`. There are two Anthropic model beans:

- **`strictChatModel`** (temp 0.1, `liteModel` = `claude-haiku-4-5-20251001`) — used by JSON-output agents: Triage, FileSelector, Analysis, Scout, Reviewer, RuleExtractor, BuildProfileInspector, DependencyPreCheck, BuildFailureClassifier.
- **`generativeChatModel`** (temp 0.3, `powerModel` = `claude-sonnet-4-6`) — used by code-generation agents: Execution, FixInstructions, BuildRepair.

`ExecutionAiService` is `@Scope("prototype")` — a fresh instance with its own chat memory (20-message window) is created per fix attempt.

Adding a new AI agent requires: (1) an `*AiService` interface, (2) a `*Service` class that calls it, (3) a `@Bean` in `AgentConfig`, and (4) injection into `FixLoopOrchestrator` or the relevant caller.

## Persistence

`IssueStore` is the **single facade** for all state transitions. Never write directly to `IssueRecordRepository` from outside `persistence/`. State flow: `PENDING → IN_PROGRESS → SUCCESS | FAILED | SKIPPED`.

Three JPA entities: `IssueRecord`, `RunLog`, `TokenUsageRecord`. Flyway migrations live in `src/main/resources/db/migration/`.

## Domain model

All types under `model/` are pure Java records with no framework dependencies. Key ones:
- `GitIssue` / `GitRepository` — input to the pipeline
- `FixReport` — persisted analysis result (also reused for execution results)
- `TriageResult` — wraps `GitIssue` with complexity and accept/reject decision

## Docker notes

`.mvn/jvm.config` pins the OrbStack socket path for Testcontainers on macOS. If running on Linux CI or Docker Desktop, these system properties are overridden by the environment (Surefire forwards `DOCKER_HOST` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`).

`DockerBuildEnvironment` is `@Scope("prototype")` — one container instance per fix attempt, cleaned up via `close()`.
