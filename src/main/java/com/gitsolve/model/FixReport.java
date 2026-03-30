package com.gitsolve.model;

import java.time.Instant;
import java.util.List;

/**
 * Structured investigation report produced for every issue the SWE Agent attempts —
 * regardless of whether the build passed or failed.
 *
 * Stored as JSONB in issue_records.fix_report and displayed on the dashboard.
 * Pure domain record — no framework dependencies.
 *
 * The {@code telemetry} field carries per-phase token counts and timing for the
 * four agent phases (Analysis, FileSelector, Execution, Reviewer). Null on
 * analysis-only reports generated before the execution phase runs.
 */
public record FixReport(
        String issueTitle,
        String issueUrl,
        String issueBodyExcerpt,    // first 500 chars of the issue body
        String rootCauseAnalysis,   // agent's explanation of the problem
        String proposedFilePath,    // file the agent modified
        String proposedCode,        // full content of the proposed fix
        String unifiedDiff,         // git diff of the change
        String buildStatus,         // "PASSED", "FAILED", or "NOT_RUN"
        int    iterationCount,
        List<String> iterationHistory,  // one summary line per iteration
        Instant generatedAt,
        TelemetryReport telemetry   // per-phase token/timing stats; null until execution completes
) {}
