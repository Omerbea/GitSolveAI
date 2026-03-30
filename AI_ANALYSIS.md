# GitSolve AI — Deep AI Systems Analysis

> Generated: 2026-03-30

---

## Pipeline Architecture (7 agents)

```
Scout → Triage → Analysis → FixInstructions → FileSelector → Execution → Reviewer
                                                              ↑ (loop, 1-5x)
```

---

## 1. Context Management

### 1.1 Two parallel context strategies (inconsistent)

**SweService** (legacy path):
- Builds context **once before the loop** from all `src/**/*.java` files
- `ContextWindowManager`: greedy first-fit in **alphabetical order** — completely ignores semantic relevance to the issue
- Hard cap: 60,000 chars; oversized files are **skipped entirely** (not truncated)

**ExecutionService** (active path):
- Rebuilds file list each iteration (good — newly written files become selectable)
- Two-step: `FileSelectorAiService` picks paths first (paths-only, no content), then `TargetedContextBuilder` reads them
- Per-file cap: **6,000 chars** (~1,500 tokens) with truncation marker
- Total cap: **30,000 chars**

**Problems:**
- `TargetedContextBuilder` caps each file at 6,000 chars, but `ExecutionAiService` is instructed to return *complete file content*. If the file was truncated in context, the LLM literally **cannot comply** — it will either hallucinate the rest or produce a partial rewrite. This is a fundamental contradiction.
- `ContextWindowManager` uses alphabetical order for a code-understanding task. `ArrayUtils.java` will always load before `ZipUtils.java` regardless of which is relevant.
- `FileSelectorAiService` does not receive the **current build error**. On iteration 2, if the fix failed because a wrong file was selected, the selector is called again with no new signal — it will pick the same files.

### 1.2 Context duplication across iterations (critical token waste)

Both `ExecutionAiService` and `SweAiService` use `MessageWindowChatMemory.withMaxMessages(20)`. Each iteration appends the full `sourceContext` again to the chat history:

```
Iteration 1: [system] + [user: sourceContext 30k chars] + [assistant: fix]
Iteration 2: [system] + [user: ctx] + [assistant] + [user: ctx again] + [assistant: fix]
Iteration 3: [system] + [user: ctx] + [ass] + [user: ctx] + [ass] + [user: ctx] + [ass: fix]
```

By iteration 3, the source context is in the prompt **3 times**. With a 20-message window and 3 max iterations configured, this triples token consumption and fills context with redundant content.

---

## 2. Prompt Quality

### 2.1 No temperature configured

```java
AnthropicChatModel.builder()
    .apiKey(...)
    .modelName(...)
    .maxTokens(16384)
    // No temperature!
    .build();
```

All 8 AI services share one model bean with default temperature (1.0 on Anthropic). For structured JSON outputs (`TriageAiService`, `FileSelectorAiService`, `ExecutionAiService`, etc.), this causes non-deterministic formatting and higher parse failure rates. JSON-structured agents need temperature ~0.1–0.2.

### 2.2 AnalysisAiService operates blind

`AnalysisAiService` receives only the issue text + triage reasoning. It identifies `affectedFiles` without seeing **any actual source code**. This produces hallucinated file paths that then flow downstream to `FixInstructionsAiService`. The analysis could confidently identify `com/example/FooService.java` that doesn't exist.

### 2.3 Parse error feedback is too vague

When `ExecutionFixParser` fails, the feedback sent back is:

```java
buildError = "Parse error: " + e.getMessage();
```

The LLM receives this as the "build error" with no visibility into what its malformed JSON looked like. It has no idea if it included markdown fences, truncated the JSON mid-file, or used wrong field names.

### 2.4 No few-shot examples

All prompts rely solely on instructions and schema descriptions. For complex structured outputs (especially `ExecutionAiService` with nested `files[]` arrays containing full file content), few-shot examples dramatically improve reliability.

### 2.5 FileSelectorAiService hard limit of 5 files

For multi-file refactors touching interfaces, implementations, and tests, the 5-file cap may force the LLM to make incomplete changes. There is no dynamic limit based on issue complexity.

---

## 3. Token Accounting

### 3.1 Budget tracking is broken

```java
tokenCount += 2_000; // rough estimate per call
```

This only accounts for the Analysis phase. Scout, Triage, FileSelector, Instructions, and Execution calls — which can easily consume 50k–200k tokens per issue — are not counted. The `maxTokensPerRun` guard provides false confidence.

LangChain4j's `AiServices` supports response metadata with real `TokenUsage`. This data is available but not collected.

### 3.2 No per-model cost differentiation

All agents use `claude-haiku-4-5-20251001`. A task like `TriageAiService` (classify EASY/MEDIUM from issue text) uses the same model as `ExecutionAiService` (write complete Java files). There is no routing to match task complexity to model capability.

---

## 4. Reliability

### 4.1 AnalysisResult.affectedFiles is not forwarded to FileSelector

`AnalysisService` produces `affectedFiles` (list of likely Java file paths). `ExecutionService` then runs `FileSelectorAiService` independently. These two signals are never combined — the FileSelector starts from scratch every time, duplicating reasoning already done.

### 4.2 No Reviewer → Execution retry loop

`ReviewerAiService` can reject a fix by returning `approved: false` with `violations[]`. But there is no code that feeds violations back to the execution agent. The rejection is a dead end.

### 4.3 Fallback file selection is semantically wrong

When `FileSelectorAiService` fails or returns empty:

```java
// Fallback: if selection is empty or failed, use first 5 files alphabetically
selectedPaths = sorted.subList(0, Math.min(FileSelectorParser.MAX_PATHS, sorted.size()));
```

Alphabetically first 5 files have nothing to do with the issue. This would be `AbstractXxx.java` files in most Java projects.

### 4.4 Shell injection in commit message

```java
String commitMsg = fix.commitMessage().replace("\"", "\\\"").replace("`", "\\`");
env.runBuild("git -C /workspace commit -m \"" + commitMsg + "\"");
```

This escaping is incomplete. A commit message containing `$(rm -rf /)` or `${IFS}` would execute in the shell. The commit message is LLM-generated, making this a real injection vector.

### 4.5 SweService vs ExecutionService duality

Both services implement an iterative build-fix loop with their own `ChatMemory`. `SweService` is single-file only; `ExecutionService` is multi-file. The pipeline does not use `SweService` actively (it's not called from `FixLoopOrchestrator`), but it's still wired and maintained, creating code duplication and confusion about which is canonical.

---

## Prioritized Recommendations

### P0 — Correctness Bugs

**1. Fix the context-truncation/complete-content contradiction**

`TargetedContextBuilder` truncates files at 6,000 chars and `ExecutionAiService` demands complete file content. Either:
- (a) Increase the per-file cap to accommodate real files, or
- (b) Change the prompt to say "modify only the shown section" and restructure the response format to use diff/patch instead of full content.

**2. Fix shell injection in commit message**

Use `git commit -F -` (read message from stdin piped through `env.writeFile`) or pass the message via a temp file, never via string interpolation into a shell command.

**3. Stop duplicating sourceContext in ChatMemory per iteration**

In `ExecutionService`, only include `sourceContext` in the **first** iteration's user message. In subsequent iterations, omit it from the explicit message body — it's already in memory. Or, better: disable `ChatMemory` entirely and instead include the full conversation delta explicitly (build error + what was tried), giving you precise control over what the LLM sees.

### P1 — Quality / Reliability

**4. Add temperature per service**

Create separate model beans for structured vs. generative tasks:
- JSON-structured agents (Triage, FileSelector, Analysis, Reviewer): temperature 0.1
- Generative agents (FixInstructions, ExecutionAiService writing code): temperature 0.3

**5. Forward AnalysisResult.affectedFiles as hints to FileSelectorAiService**

Pass the analysis-identified files as a priority hint in the file selector prompt:
> "These files were identified by preliminary analysis — prefer them."

Eliminates duplicated reasoning across two agents.

**6. Feed FileSelectorAiService the current build error**

On iteration > 1, include the build error in the file selector call so it can reason:
> "The fix failed with a NoSuchMethodError in class X — I need to also select X.java."

**7. Replace alphabetical fallback with issue-keyword matching**

When FileSelectorAiService fails, filter the file list by keywords extracted from the issue title/body rather than sorting alphabetically.

**8. Give AnalysisAiService actual source code**

After triage returns `affectedFiles`, fetch the content of those files (or use the repo clone from cache) and include it in the analysis prompt. Analysis that sees real code will produce accurate root causes, not hallucinated ones.

### P2 — Cost / Observability

**9. Track real token usage from LangChain4j response metadata**

LangChain4j's `TokenStream.onComplete(Response<AiMessage> r)` gives `r.tokenUsage()` with input/output/total counts. Use these to update `tokenCount` accurately and enforce the budget across all agents.

**10. Introduce per-agent model routing**

Route low-complexity agents to smaller/cheaper models and high-stakes agents to more capable ones:

| Agent | Recommended model |
|---|---|
| Triage, FileSelector | `claude-haiku-4-5` or cheaper |
| Analysis, FixInstructions | `claude-sonnet-4-6` |
| ExecutionAiService | `claude-sonnet-4-6` (accuracy critical) |
| Reviewer | `claude-haiku-4-5` |

**11. Wire Reviewer violations back to Execution**

When `ReviewerAiService` returns `approved: false`, pass `violations[]` as additional context to the next execution iteration. This closes the quality loop that is currently open.

**12. Retire or clearly isolate SweService**

If `SweService` is not used in the active pipeline, mark it `@Deprecated` or remove it. The duplication creates maintenance burden and confusion about which fix loop is authoritative.

---

## Summary Table

| Area | Severity | Issue |
|---|---|---|
| Context | Critical | Truncated context + demand for complete content is contradictory |
| Context | High | Source context duplicated in ChatMemory each iteration |
| Context | Medium | Alphabetical file ordering ignores issue relevance |
| Prompts | High | No temperature set — JSON agents are non-deterministic |
| Prompts | High | Analysis agent never sees real source code |
| Prompts | Medium | Parse errors give LLM no diagnostic signal |
| Tokens | High | Budget tracking counts only ~5% of real token usage |
| Tokens | Medium | Same model for triage and code generation |
| Reliability | Critical | Shell injection via LLM-generated commit message |
| Reliability | High | FileSelector ignores build error signal on retry iterations |
| Reliability | Medium | Reviewer rejection has no retry path |
| Reliability | Medium | Alphabetical fallback selection is semantically useless |
| Architecture | Medium | AnalysisResult.affectedFiles never forwarded to FileSelector |
| Architecture | Low | SweService duality — legacy code not cleaned up |
