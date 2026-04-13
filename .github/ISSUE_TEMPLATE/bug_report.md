---
name: Bug report
about: Report a reproducible defect in GitSolve AI
title: "[Bug] "
labels: bug
assignees: ""
---

## Describe the bug

A clear and concise description of what the bug is.

## Steps to reproduce

1. Go to '...'
2. Configure '...'
3. Run '...'
4. See error

## Expected behaviour

What you expected to happen.

## Actual behaviour

What actually happened. Include the full stack trace and relevant log output.

```
paste logs here
```

## Environment

| Field | Value |
|-------|-------|
| GitSolve AI version / commit | |
| Java version (`java --version`) | |
| Docker version (`docker version`) | |
| Operating system | |
| LLM provider (`anthropic` / `gemini`) | |

## Configuration

Paste the relevant parts of your `application.yml` or environment variable overrides below. **Redact all secrets and API tokens before posting.**

```yaml
gitsolve:
  github:
    max-repos-per-run: ...
  llm:
    provider: ...
```

## Additional context

Any other context, screenshots, or links that might help diagnose the problem.
