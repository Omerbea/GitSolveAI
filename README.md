# GitSolve AI

Autonomous Java open-source issue fixer. Discovers active GitHub repositories, triages good-first issues by complexity, generates code fixes inside isolated Docker containers using LangChain4j AI agents, validates fixes against repository governance rules, persists all state in PostgreSQL, and presents results in a local HTML dashboard.

> **Status:** Core pipeline complete. All agents implemented and tested. Local dashboard live.

---

## How it works

```
┌─────────────┐   repos + issues   ┌──────────────┐   accepted issues   ┌─────────────────────┐
│ Scout Agent │──────────────────▶ │ Triage Agent │───────────────────▶ │  Fix Loop           │
└─────────────┘                    └──────────────┘                      │  Orchestrator       │
       │                                                                   │  (@Scheduled cron)  │
       │ GitHub API                                                        └──────────┬──────────┘
       ▼                                                                              │
   GitHub REST                                                           ┌────────────▼────────────┐
                                                                         │      SWE Agent          │
                                                                         │  (Docker sandbox)       │
                                                                         │  clone → context → LLM  │
                                                                         │  → fix → build → repeat │
                                                                         └────────────┬────────────┘
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

### Agent responsibilities

| Agent | What it does |
|-------|-------------|
| **Scout** | Calls the GitHub Search API (via LangChain4j tool-use) to discover the top N active Java repos with `good-first-issue` tickets. Scores repos by velocity (recent commit count, stars, forks). |
| **Triage** | Classifies each issue as `EASY` or `MEDIUM`. Applies deterministic pre/post-LLM rejection rules (too short, WIP, UI work, DB migration, MEDIUM without clear steps). |
| **SWE Agent** | Runs an iterative fix loop inside an isolated Docker container: clone → build context (60k-char budget) → stream LLM fix → write file → `mvn test` → iterate on failure. Returns a `FixResult` with the unified diff and all attempt history. |
| **Reviewer** | Fetches `CONTRIBUTING.md` via GitHub API, extracts governance constraints (forbidden patterns, DCO requirement, required tests, JDK version) using an LLM, then validates the fix diff against those constraints. |
| **Orchestrator** | `@Scheduled` daily cron: Scout → Triage → per-issue SWE + Reviewer loop, with idempotency checks, token-budget enforcement, and IssueStore state transitions. |

---

## Tech stack

| Layer | Technology |
|-------|-----------|
| Runtime | Java 21, Spring Boot 3.4.4, virtual threads |
| AI | LangChain4j 0.36 — Anthropic Claude or Google Gemini |
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
| Java (Temurin) | 21 | `java --version` |
| Maven | 3.9+ | `mvn --version` |
| Docker | any recent | `docker version` |

**macOS:** [OrbStack](https://orbstack.dev) is recommended. The socket path is pinned in `.mvn/jvm.config` — no additional configuration needed for Testcontainers.

---

## Quick start

```bash
# 1. Clone
git clone <repo-url>
cd gitsolve-ai

# 2. Start Postgres (and optional OTel collector)
docker compose up -d postgres

# 3. Export required secrets
export GITHUB_APP_TOKEN=ghp_...          # GitHub App installation token
export ANTHROPIC_API_KEY=sk-ant-...      # If using Anthropic (default)
# export GEMINI_API_KEY=...              # If using Gemini instead

# 4. Run unit tests (fast, no Docker required)
mvn test -Punit

# 5. Start the application
mvn spring-boot:run
```

Dashboard: [http://localhost:8080/](http://localhost:8080/)
Health: [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)

---

## Environment variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `GITHUB_APP_TOKEN` | **Yes** | — | GitHub App installation token. App must have `contents: read` and `issues: read` only. |
| `ANTHROPIC_API_KEY` | If `provider=anthropic` | — | Anthropic API key. |
| `GEMINI_API_KEY` | If `provider=gemini` | — | Google AI Studio key. |
| `DATABASE_URL` | No | `jdbc:postgresql://localhost:5432/gitsolve` | PostgreSQL JDBC URL. |
| `DATABASE_USER` | No | `gitsolve` | DB username. |
| `DATABASE_PASSWORD` | No | `gitsolve` | DB password. |
| `DOCKER_HOST` | No | auto-detected | Docker socket path. Set to `unix:///Users/you/.orbstack/run/docker.sock` on OrbStack. |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | No | `http://localhost:4318/v1/traces` | OpenTelemetry collector endpoint. |

---

## Configuration

All settings are in `src/main/resources/application.yml`. Key knobs:

```yaml
gitsolve:
  github:
    max-repos-per-run: 10        # repos scouted per run
    max-issues-per-repo: 5       # issues fetched per repo

  llm:
    provider: anthropic          # "anthropic" or "gemini"
    model: claude-3-5-sonnet-20241022
    max-tokens-per-run: 500000   # daily token budget (approximate)
    max-iterations-per-fix: 5    # SWE Agent retry limit per issue

  docker:
    image: eclipse-temurin:21-jdk
    mem-limit-mb: 1024
    cpu-quota: 50000             # 50% of one core
    build-timeout-seconds: 300

  schedule:
    cron: "0 0 0 * * *"         # daily at midnight UTC
```

To switch to Gemini:

```yaml
gitsolve:
  llm:
    provider: gemini
    model: gemini-1.5-pro
```

---

## Running tests

```bash
# Unit tests — fast, no external dependencies (~10s)
mvn test -Punit

# Persistence integration tests — real Postgres via Testcontainers (~10s)
DOCKER_HOST=unix:///path/to/docker.sock mvn test -Ppersistence

# Docker sandbox integration tests — real container + git clone (~90s)
DOCKER_HOST=unix:///path/to/docker.sock mvn test -Pintegration

# Full suite (unit + all integration)
DOCKER_HOST=unix:///path/to/docker.sock mvn clean verify
```

### Test profiles

| Profile | Tag | What runs | Time |
|---------|-----|-----------|------|
| `-Punit` | `unit` | All unit tests — pure Mockito, no I/O | ~10s |
| `-Ppersistence` | `persistence` | IssueStore + RunLog Postgres integration tests | ~10s |
| `-Pintegration` | `integration` | All integration tests including Docker sandbox | ~90s |

### CI

GitHub Actions runs `mvn clean verify` on every push. Docker is available on `ubuntu-latest` runners; Testcontainers auto-detects the daemon. No secrets are needed — unit and integration tests use placeholder tokens.

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

Migrations are in `src/main/resources/db/migration/`.

---

## Dashboard

The local dashboard is served by Thymeleaf at `http://localhost:8080/`.

| Route | Description |
|-------|-------------|
| `GET /` | Fix history table (SUCCESS + FAILED issues), run statistics, recent runs |
| `GET /issues/{id}/diff` | Unified diff view for a completed fix |

The dashboard is **read-only** and intended for local developer use. It has no authentication.

---

## Observability

| Endpoint | Description |
|----------|-------------|
| `GET /actuator/health` | Application health (DB connectivity, disk) |
| `GET /actuator/metrics` | All Micrometer metrics |
| `GET /actuator/prometheus` | Prometheus scrape endpoint |

### Key metrics

| Metric | Description |
|--------|-------------|
| `gitsolve_issues_attempted_total{agent}` | Issues entering the fix loop |
| `gitsolve_issues_succeeded_total` | Issues successfully fixed and approved |
| `gitsolve_issues_failed_total{reason}` | Failed issues, labelled by reason (`swe_failed`, `reviewer_rejected`, `docker_error`) |
| `gitsolve_fix_iterations` | Distribution of SWE Agent iteration counts |
| `gitsolve_tokens_used_total{agent,model}` | Cumulative LLM token consumption |
| `gitsolve_agent_duration_seconds{operation,success}` | Per-operation latency histogram |

### Traces

OTel traces are exported via OTLP HTTP to `$OTEL_EXPORTER_OTLP_ENDPOINT` (default: `http://localhost:4318/v1/traces`). Run the full observability stack locally:

```bash
docker compose up -d    # starts Postgres + OTel collector
```

---

## Project structure

```
src/main/java/com/gitsolve/
├── GitSolveApplication.java          # entry point, @EnableScheduling
├── agent/
│   ├── scout/                        # ScoutAiService, ScoutService, ScoutTools, VelocityScoreCalculator
│   ├── triage/                       # TriageAiService, TriageService, IssueSanitizer, TriageResponse
│   ├── swe/                          # SweAiService, SweService, SweFixParser, SweFixResponse,
│   │                                 #   SweParseException, ContextWindowManager
│   └── reviewer/                     # ReviewerAiService, ReviewerService, RuleExtractorAiService,
│                                     #   RuleExtractorService, ReviewResponse, ReviewerParseException
├── config/
│   ├── AgentConfig.java              # all LangChain4j AiService @Bean declarations
│   ├── DockerClientConfig.java       # DockerClient bean (ZerodepDockerHttpClient)
│   ├── GitHubClientConfig.java       # WebClient for GitHub API
│   └── GitSolveProperties.java       # @ConfigurationProperties — all settings
├── dashboard/
│   └── DashboardController.java      # GET /, GET /issues/{id}/diff
├── docker/
│   ├── BuildEnvironment.java         # interface: clone, read, list, write, build, diff, close
│   ├── DockerBuildEnvironment.java   # @Scope(prototype) implementation
│   ├── BuildOutput.java              # record: stdout, stderr, exitCode, timedOut
│   └── BuildEnvironmentException.java
├── github/
│   ├── GitHubClient.java             # searchJavaRepos, getGoodFirstIssues, getFileContent, getRateLimit
│   └── dto/                          # GitHubRepoDto, GitHubIssueDto, GitHubSearchResponse, …
├── model/                            # pure domain records — no framework dependencies
│   ├── GitIssue.java                 # repoFullName, issueNumber, title, body, htmlUrl, labels
│   ├── GitRepository.java            # fullName, cloneUrl, starCount, velocityScore, …
│   ├── FixResult.java                # issueId, success, attempts, finalDiff, failureReason
│   ├── FixAttempt.java               # iterationNumber, modifiedFilePath, patchDiff, buildOutput, buildPassed
│   ├── ReviewResult.java             # approved, violations, summary
│   ├── ConstraintJson.java           # checkstyleConfig, requiresDco, requiresTests, jdkVersion, …
│   ├── TriageResult.java             # issue, complexity, reasoning, accepted
│   └── RunStats.java                 # pending, inProgress, succeeded, failed, skipped
├── orchestration/
│   └── FixLoopOrchestrator.java      # @Scheduled daily pipeline
├── persistence/
│   ├── IssueStore.java               # THE persistence facade — all state transitions go through here
│   ├── entity/                       # IssueRecord, RunLog, TokenUsageRecord (JPA entities)
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

Create a GitHub App with the following permissions only:

| Permission | Level |
|------------|-------|
| `Contents` | Read |
| `Issues` | Read |

**Do not grant write permissions.** GitSolve AI reads issues and repository files — it does not open pull requests or comment on issues in the current version.

Install the app on the repositories you want to target and export the installation token:

```bash
export GITHUB_APP_TOKEN=<installation-token>
```

---

## Known limitations and deferred work

| Area | Status | Notes |
|------|--------|-------|
| Network isolation in Docker containers | Deferred to M8 | Containers run in bridge mode because `apt-get install git` requires outbound network access. `networkMode=none` and `capDrop(ALL)` will be re-enabled once a pre-built image with git is used. |
| Accurate token counting | Partial | Token budget uses a per-iteration estimate (1,000 tokens/iteration). Real per-agent token counts via LangChain4j response metadata are a future enhancement. |
| Pull request submission | Not implemented | Fixes are stored in the database only. A future PR-submission agent would open a pull request with the validated diff. |
| OpenTelemetry per-agent traces | Schema ready | The `token_usage` table and Micrometer metrics are wired; end-to-end OTel spans across all agents are not yet propagated. |

---

## License

MIT
