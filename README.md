# GitSolve AI

> Autonomous GitHub issue fixer — discovers OSS repos, triages good-first issues, generates code fixes inside isolated Docker containers using LLM agents, and presents results in a local dashboard.

[![CI](https://github.com/your-org/gitsolve-ai/actions/workflows/ci.yml/badge.svg)](https://github.com/your-org/gitsolve-ai/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-blue?logo=openjdk)](https://adoptium.net/)
[![Spring Boot 3](https://img.shields.io/badge/Spring%20Boot-3.4-green?logo=spring)](https://spring.io/projects/spring-boot)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## Why this exists

Triaging and fixing `good-first-issue` tickets is valuable but time-consuming. GitSolve AI automates the full pipeline: it scouts active Java repositories on GitHub, decides which issues are worth attempting, generates a code fix in a sandboxed Docker container, validates the fix against the repository's own contributing rules, and stores every result for review — without any human involvement until you decide to open a pull request.

---

## How it works

```
┌─────────────┐   repos + issues   ┌──────────────┐   accepted issues   ┌─────────────────────┐
│ Scout Agent │──────────────────▶ │ Triage Agent │───────────────────▶ │  Fix Loop           │
└─────────────┘                    └──────────────┘                      │  Orchestrator       │
       │                                                                   │  (@Scheduled cron)  │
       │ GitHub Search API                                                 └──────────┬──────────┘
       ▼                                                                              │
   GitHub REST                                                           ┌────────────▼────────────┐
                                                                         │  Analysis Agent         │
                                                                         │  root cause + files     │
                                                                         │  + approach             │
                                                                         └────────────┬────────────┘
                                                                                      │
                                                                         ┌────────────▼────────────┐
                                                                         │  Fix Instructions       │
                                                                         │  (repo coding rules)    │
                                                                         └────────────┬────────────┘
                                                                                      │
                                                    ┌─────────────────────────────────▼───────────────────────────────┐
                                                    │  [User-initiated via dashboard: POST /issues/{id}/execute]      │
                                                    │  SWE Agent (Docker sandbox)                                     │
                                                    │  clone → build context (60k-char) → LLM fix →                  │
                                                    │  write file → mvn test → iterate on failure                    │
                                                    └─────────────────────────────────┬───────────────────────────────┘
                                                                                      │ FixResult
                                                                         ┌────────────▼────────────┐
                                                                         │    Reviewer Agent       │
                                                                         │  CONTRIBUTING.md rules  │
                                                                         │  → approve / reject     │
                                                                         └────────────┬────────────┘
                                                                                      │ ReviewResult
                                                                    ┌─────────────────▼──────────────────┐
                                                                    │  PostgreSQL (IssueStore)            │
                                                                    │  issue_records · run_logs           │
                                                                    │  token_usage                        │
                                                                    └─────────────────┬──────────────────┘
                                                                                      │
                                                                    ┌─────────────────▼──────────────────┐
                                                                    │  Local Dashboard (Thymeleaf)        │
                                                                    │  GET /  ·  GET /issues/{id}/diff    │
                                                                    └────────────────────────────────────┘
```

### Pipeline stages

| Stage | Trigger | What it does |
|-------|---------|--------------|
| Scout | Cron (daily midnight UTC) | Calls the GitHub Search API via LangChain4j tool-use to discover active Java repos. Bypassed entirely if `gitsolve.github.target-repos` is set. |
| Triage | Cron | Classifies each issue as `EASY` or `MEDIUM`. `IssueSanitizer` applies deterministic pre/post-LLM rejection rules before any LLM call. |
| Analysis | Cron | Produces a structured investigation report: root cause, affected files, suggested approach. Persisted as a `FixReport`. |
| Fix Instructions | Cron | Generates repo-specific coding instructions derived from the repository's own style and governance files. |
| Execution | User (dashboard) | Runs the fix loop inside an isolated Docker container: clone → 60k-char context budget → LLM fix → write file → `mvn test` → iterate up to `max-iterations-per-fix` times. |
| Review | User (dashboard) | Fetches `CONTRIBUTING.md`, extracts governance constraints via LLM, then validates the fix diff against those constraints. |

---

## Tech stack

| Layer | Technology |
|-------|-----------|
| Runtime | Java 21, Spring Boot 3.4, virtual threads |
| AI | LangChain4j 0.36 — Anthropic Claude (Haiku for JSON agents, Sonnet for code generation) |
| Build sandbox | docker-java 3.3 — isolated container per fix attempt |
| Database | PostgreSQL 16, Flyway migrations, Spring Data JPA |
| Web | Spring MVC, Thymeleaf |
| Observability | Micrometer, Prometheus, OpenTelemetry (OTLP) |
| Testing | JUnit 5, Mockito, Testcontainers 1.20, WireMock |
| Build | Maven 3.9, GitHub Actions CI |

---

## Prerequisites

| Tool | Version | Check |
|------|---------|-------|
| Java (Temurin) | 21+ | `java --version` |
| Maven | 3.9+ | `mvn --version` |
| Docker | any recent | `docker version` |
| PostgreSQL | 16 (via Docker Compose) | — |

**macOS users:** [OrbStack](https://orbstack.dev) is recommended over Docker Desktop. The OrbStack socket path is pre-configured in `.mvn/jvm.config` — no additional setup is required for Testcontainers.

---

## Quick start

```bash
# 1. Clone the repository
git clone https://github.com/your-org/gitsolve-ai.git
cd gitsolve-ai

# 2. Start PostgreSQL
docker compose up -d postgres

# 3. Set required environment variables
export GITHUB_APP_TOKEN=ghp_...          # GitHub PAT (repo + search scope)
export ANTHROPIC_API_KEY=sk-ant-...      # Anthropic API key

# 4. Verify your setup with unit tests (no Docker or live API calls needed)
mvn test -Punit

# 5. Start the application
mvn spring-boot:run
```

Open the dashboard at [http://localhost:8080/](http://localhost:8080/)  
Health check at [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)

The scheduled pipeline runs automatically at midnight UTC. To trigger it manually, restart the application — the `@Scheduled` method fires on startup if the next scheduled time is past.

---

## Environment variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `GITHUB_APP_TOKEN` | Yes | — | GitHub PAT with `repo` and `search` scope. A GitHub App installation token also works — the app needs `Contents: read` and `Issues: read` only. |
| `ANTHROPIC_API_KEY` | Yes (default provider) | — | Anthropic API key. Required when `gitsolve.llm.provider=anthropic`. |
| `GEMINI_API_KEY` | If using Gemini | — | Google AI Studio key. Required when `gitsolve.llm.provider=gemini`. |
| `DATABASE_URL` | No | `jdbc:postgresql://localhost:5432/gitsolve` | PostgreSQL JDBC URL. |
| `DATABASE_USER` | No | `gitsolve` | Database username. |
| `DATABASE_PASSWORD` | No | `gitsolve` | Database password. |
| `DOCKER_HOST` | No | auto-detected | Docker socket path. Set explicitly on OrbStack: `unix:///Users/you/.orbstack/run/docker.sock`. |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | No | `http://localhost:4318/v1/traces` | OpenTelemetry collector endpoint for distributed traces. |

---

## Configuration reference

All settings live in `src/main/resources/application.yml` under the `gitsolve` prefix.

### GitHub settings

| Property | Default | Description |
|----------|---------|-------------|
| `gitsolve.github.app-token` | (from `GITHUB_APP_TOKEN`) | GitHub token — prefer the environment variable. |
| `gitsolve.github.max-repos-per-run` | `2` | Maximum repositories scouted per cron run. |
| `gitsolve.github.max-issues-per-repo` | `5` | Maximum issues fetched per repository. |
| `gitsolve.github.target-repos` | _(empty)_ | Pin specific repos (e.g. `apache/commons-lang`). When set, the LLM scout is bypassed entirely. |
| `gitsolve.github.star-range` | `"100..3000"` | Star range used when `target-repos` is empty. Avoids always picking the same mega-repos. |

### LLM settings

| Property | Default | Description |
|----------|---------|-------------|
| `gitsolve.llm.provider` | `anthropic` | LLM provider. Supported values: `anthropic`, `gemini`. |
| `gitsolve.llm.lite-model` | `claude-haiku-4-5-20251001` | Model used for JSON-output agents (Triage, Analysis, Scout, Reviewer). Temperature 0.1. |
| `gitsolve.llm.power-model` | `claude-sonnet-4-6` | Model used for code-generation agents (Execution, FixInstructions, BuildRepair). Temperature 0.3. |
| `gitsolve.llm.max-tokens-per-run` | `500000` | Daily token budget (approximate). The pipeline stops accepting new issues when this is exceeded. |
| `gitsolve.llm.max-iterations-per-fix` | `3` | Maximum SWE Agent retry iterations per issue before marking the issue as `FAILED`. |

### Docker sandbox settings

| Property | Default | Description |
|----------|---------|-------------|
| `gitsolve.docker.image` | `eclipse-temurin:21-jdk` | Base Docker image for each fix attempt. |
| `gitsolve.docker.mem-limit-mb` | `1024` | Memory cap for each sandbox container in MB. |
| `gitsolve.docker.cpu-quota` | `50000` | CPU quota in microseconds (50000 = 50% of one core). |
| `gitsolve.docker.build-timeout-seconds` | `600` | Maximum time for a single `mvn test` run inside the container. |

### Scheduler settings

| Property | Default | Description |
|----------|---------|-------------|
| `gitsolve.schedule.cron` | `0 0 0 * * *` | Cron expression for the pipeline. Default: daily at midnight UTC. |

### Repository cache settings

| Property | Default | Description |
|----------|---------|-------------|
| `gitsolve.repo-cache.base-dir` | `~/.gitsolve-repos` | Local directory where cloned repositories are cached between runs. |
| `gitsolve.repo-cache.update-existing` | `true` | When `true`, cached repos are updated via `git fetch` instead of re-cloned on each run. |

**Example: pin specific repos and increase iteration limit**

```yaml
gitsolve:
  github:
    target-repos:
      - apache/commons-lang
      - javaparser/javaparser
  llm:
    max-iterations-per-fix: 5
```

**Example: switch to Gemini**

```yaml
gitsolve:
  llm:
    provider: gemini
    lite-model: gemini-1.5-flash
    power-model: gemini-1.5-pro
```

---

## Running tests

```bash
# Unit tests — pure Mockito, no I/O (~10s)
mvn test -Punit

# Persistence integration tests — real PostgreSQL via Testcontainers (~10s)
mvn test -Ppersistence

# Docker sandbox integration tests — real container + git clone (~90s)
mvn test -Pintegration

# Full suite
mvn clean verify
```

### Test profiles

| Profile | JUnit tag | What runs | Time |
|---------|-----------|-----------|------|
| `-Punit` | `unit` | All unit tests — pure Mockito, no I/O | ~10s |
| `-Ppersistence` | `persistence` | `IssueStore` + `RunLog` PostgreSQL integration tests | ~10s |
| `-Pintegration` | `integration` | All integration tests including Docker sandbox | ~90s |

Every test class must carry exactly one `@Tag` (`unit`, `persistence`, or `integration`). The Surefire plugin filters by tag via `<groups>` in `pom.xml`.

### CI

GitHub Actions runs `mvn clean verify` on every push and pull request. Docker is available on `ubuntu-latest` runners and Testcontainers auto-detects the daemon. No live API keys are required in CI — unit and integration tests use placeholder tokens.

---

## Dashboard

The dashboard is served at `http://localhost:8080/` and is intended for local developer use only. It has no authentication.

| Route | Method | Description |
|-------|--------|-------------|
| `/` | GET | Fix history table (SUCCESS + FAILED issues), run statistics, recent cron runs |
| `/issues/{id}/diff` | GET | Unified diff view for a completed fix |
| `/issues/{id}/execute` | POST | Trigger execution for a fully analyzed issue |

---

## Database schema

Three tables managed by Flyway:

```
issue_records   — one row per GitHub issue processed
  id, issue_id, repo_url, repo_full_name, issue_number, issue_title, issue_body
  complexity (EASY|MEDIUM), status (PENDING|IN_PROGRESS|SUCCESS|FAILED|SKIPPED)
  failure_reason, fix_diff (TEXT), fix_summary, constraint_json (JSONB)
  iteration_count, created_at, updated_at, completed_at

run_logs        — one row per daily orchestrator run
  id, run_id (UUID), started_at, finished_at
  issues_scouted, issues_triaged, issues_attempted, issues_succeeded
  token_usage, status (RUNNING|COMPLETED|FAILED)

token_usage     — one row per agent LLM call
  id, run_id (UUID), agent_name, model
  input_tokens, output_tokens, recorded_at
```

Migrations live in `src/main/resources/db/migration/`.

---

## Observability

| Endpoint | Description |
|----------|-------------|
| `GET /actuator/health` | Application health (DB connectivity, disk) |
| `GET /actuator/metrics` | All Micrometer metrics |
| `GET /actuator/prometheus` | Prometheus scrape endpoint |

Key metrics:

| Metric | Description |
|--------|-------------|
| `gitsolve_issues_attempted_total{agent}` | Issues entering the fix loop |
| `gitsolve_issues_succeeded_total` | Issues successfully fixed and approved |
| `gitsolve_issues_failed_total{reason}` | Failed issues, labelled by reason (`swe_failed`, `reviewer_rejected`, `docker_error`) |
| `gitsolve_fix_iterations` | Distribution of SWE Agent iteration counts |
| `gitsolve_tokens_used_total{agent,model}` | Cumulative LLM token consumption |
| `gitsolve_agent_duration_seconds{operation,success}` | Per-operation latency histogram |

To run the full local observability stack (PostgreSQL + OTel collector):

```bash
docker compose up -d
```

---

## Project structure

```
src/main/java/com/gitsolve/
├── GitSolveApplication.java          # entry point, @EnableScheduling
├── agent/
│   ├── scout/                        # ScoutAiService, ScoutService, ScoutTools, VelocityScoreCalculator
│   ├── triage/                       # TriageAiService, TriageService, IssueSanitizer
│   ├── analysis/                     # AnalysisAiService, AnalysisService
│   ├── instructions/                 # InstructionsAiService, InstructionsService
│   ├── swe/                          # SweAiService, SweService, SweFixParser, ContextWindowManager
│   └── reviewer/                     # ReviewerAiService, ReviewerService, RuleExtractorAiService
├── config/
│   ├── AgentConfig.java              # all LangChain4j AiService @Bean declarations
│   ├── DockerClientConfig.java       # DockerClient bean
│   ├── GitHubClientConfig.java       # WebClient for GitHub API
│   └── GitSolveProperties.java       # @ConfigurationProperties — all gitsolve.* settings
├── dashboard/
│   └── DashboardController.java      # GET /, GET /issues/{id}/diff, POST /issues/{id}/execute
├── docker/
│   ├── BuildEnvironment.java         # interface: clone, read, list, write, build, diff, close
│   ├── DockerBuildEnvironment.java   # @Scope("prototype") implementation
│   └── BuildOutput.java             # record: stdout, stderr, exitCode, timedOut
├── github/
│   └── GitHubClient.java             # searchJavaRepos, getGoodFirstIssues, getFileContent
├── model/                            # pure domain records — no framework dependencies
│   ├── GitIssue.java / GitRepository.java
│   ├── FixResult.java / FixAttempt.java / FixReport.java
│   ├── ReviewResult.java / ConstraintJson.java
│   └── TriageResult.java / RunStats.java
├── orchestration/
│   └── FixLoopOrchestrator.java      # @Scheduled daily pipeline
├── persistence/
│   ├── IssueStore.java               # single facade for all state transitions
│   ├── entity/                       # IssueRecord, RunLog, TokenUsageRecord
│   └── repository/                   # Spring Data JPA repositories
└── telemetry/
    └── AgentMetrics.java             # Micrometer counters, histograms, timers

src/main/resources/
├── application.yml
├── templates/
│   ├── dashboard/index.html          # fix history + stats
│   └── dashboard/diff.html           # unified diff view
└── db/migration/
    ├── V1__create_issues.sql
    ├── V2__create_run_logs.sql
    ├── V3__create_token_usage.sql
    └── V4__updated_at_trigger.sql
```

---

## GitHub App setup

Create a GitHub App (or use a Personal Access Token) with these permissions only:

| Permission | Level |
|------------|-------|
| `Contents` | Read |
| `Issues` | Read |

GitSolve AI reads issues and repository files. It does not open pull requests or post comments in the current version — do not grant write permissions.

Export the token before starting the application:

```bash
export GITHUB_APP_TOKEN=<your-token>
```

---

## Known limitations

| Area | Notes |
|------|-------|
| Network isolation in containers | Containers run in bridge mode because `apt-get install git` requires outbound network access. `networkMode=none` will be re-enabled once a pre-built image with git is used. |
| Token counting | The daily budget uses a per-iteration estimate. Exact per-agent counts via LangChain4j response metadata are a future enhancement. |
| Pull request submission | Fixes are stored in the database only. A PR-submission agent that opens pull requests with validated diffs is not yet implemented. |
| OTel per-agent traces | The `token_usage` table and Micrometer metrics are wired; end-to-end OTel spans across all agents are not yet propagated. |

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, branch conventions, test requirements, and how to propose changes.

---

## License

[MIT](LICENSE)
