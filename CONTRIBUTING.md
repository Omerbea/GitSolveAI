# Contributing to GitSolve AI

Thank you for your interest in contributing. This document explains how to report issues, propose features, set up a development environment, and submit pull requests.

---

## Code of conduct

This project follows the [Contributor Covenant](https://www.contributor-covenant.org/version/2/1/code_of_conduct/) Code of Conduct. By participating you agree to uphold it. Please report unacceptable behaviour to the project maintainers via GitHub private contact.

---

## Reporting a bug

Before opening an issue, search [existing issues](../../issues) to avoid duplicates.

When reporting a bug, include:

- **GitSolve AI version / commit SHA** you are running
- **Java version** (`java --version`)
- **Docker version** (`docker version`)
- **Operating system and version**
- **Steps to reproduce** — the shortest sequence that reliably triggers the problem
- **Expected behaviour** — what you expected to happen
- **Actual behaviour** — what actually happened, including full stack traces and log output
- **Relevant configuration** — redact all secrets before pasting `application.yml` snippets or environment variables

Use the [bug report template](.github/ISSUE_TEMPLATE/bug_report.md).

---

## Proposing a feature

Open a [feature request](.github/ISSUE_TEMPLATE/feature_request.md) and describe:

- The problem you are solving (not the solution)
- The proposed behaviour and any alternative approaches you considered
- Whether you are willing to implement it yourself

Maintainers will confirm scope and design before significant implementation work begins.

---

## Development setup

### Prerequisites

| Tool | Version |
|------|---------|
| Java (Temurin) | 21+ |
| Maven | 3.9+ |
| Docker | any recent (OrbStack recommended on macOS) |

### Steps

```bash
# 1. Fork and clone
git clone https://github.com/your-fork/gitsolve-ai.git
cd gitsolve-ai

# 2. Start PostgreSQL
docker compose up -d postgres

# 3. Set environment variables
export GITHUB_APP_TOKEN=ghp_...
export ANTHROPIC_API_KEY=sk-ant-...

# 4. Run unit tests to confirm the baseline
mvn test -Punit

# 5. Start the application
mvn spring-boot:run
```

All three test profiles must pass before submitting a PR:

```bash
mvn test -Punit          # pure unit tests (~10s)
mvn test -Ppersistence   # PostgreSQL integration tests (~10s)
mvn test -Pintegration   # Docker sandbox integration tests (~90s)
```

---

## Adding a new AI agent

The architecture requires four artifacts per agent:

1. An `*AiService` interface (annotated with LangChain4j `@AiServices` or `@SystemMessage`)
2. A `*Service` class that wraps it and handles error/retry logic
3. A `@Bean` declaration in `AgentConfig.java` wiring the correct model (`strictChatModel` for JSON-output agents, `generativeChatModel` for code-generation agents)
4. Injection into `FixLoopOrchestrator` or the appropriate caller

All new JSON-output agents must use `strictChatModel` (Haiku, temperature 0.1). Code-generation agents use `generativeChatModel` (Sonnet, temperature 0.3).

---

## Persistence rules

`IssueStore` is the **only** entry point for all state transitions. Do not write directly to `IssueRecordRepository` or any other repository from outside the `persistence/` package. State flows strictly as:

```
PENDING → IN_PROGRESS → SUCCESS | FAILED | SKIPPED
```

---

## Branch naming

```
feat/short-description       # new feature
fix/short-description        # bug fix
refactor/short-description   # refactoring with no behaviour change
docs/short-description       # documentation only
test/short-description       # test additions or corrections
chore/short-description      # build, CI, dependency updates
```

Branch off `main`. Rebase onto `main` before opening your PR — do not merge `main` into your branch.

---

## Commit messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>: <short imperative description>

<optional body — explain why, not what>

<optional footer — e.g. Closes #123>
```

Types: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `perf`, `ci`

Examples:

```
feat: add DependencyPreCheckService to validate pom.xml before execution
fix: handle null FixReport when issue is in SKIPPED state
test: add unit tests for IssueSanitizer rejection rules
```

Keep the subject line under 72 characters. Use the body for context that cannot fit in the subject.

---

## Test requirements

Every new or changed code path must be covered by at least one test. Choose the appropriate tag:

| Tag | Profile | Use when |
|-----|---------|----------|
| `unit` | `-Punit` | No I/O, no database, no Docker — use Mockito |
| `persistence` | `-Ppersistence` | Tests that require a real PostgreSQL database |
| `integration` | `-Pintegration` | Tests that require a real Docker container |

Each test class must carry **exactly one** `@Tag`. The Surefire plugin filters by tag — a class without a tag will not run in any profile.

```java
@Tag("unit")
class IssueSanitizerTest {
    // ...
}
```

The CI pipeline runs `mvn clean verify`, which executes all three profiles. Your PR will not be merged if any test fails.

---

## Pull request checklist

Before marking your PR as ready for review:

- [ ] All three test profiles pass locally (`-Punit`, `-Ppersistence`, `-Pintegration`)
- [ ] New behaviour is covered by at least one `@Tag("unit")` test
- [ ] No hardcoded credentials or real API tokens in any file
- [ ] No `System.out.println` or temporary debug statements
- [ ] Commit messages follow the Conventional Commits format
- [ ] The PR description explains the problem being solved and links to the related issue

Fill out the [PR template](.github/PULL_REQUEST_TEMPLATE.md) when opening your pull request.

---

## CI

GitHub Actions runs on every push and pull request. The pipeline executes `mvn clean verify`. Docker is available on `ubuntu-latest` runners — no additional configuration is needed.

All CI checks must be green before a PR can be merged.
