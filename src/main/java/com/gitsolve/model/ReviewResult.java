package com.gitsolve.model;

import java.util.List;

/**
 * Output of the Reviewer Agent's validation of a fix against governance constraints.
 * Pure domain record — no framework dependencies.
 *
 * <p>The {@code phaseStats} field carries LLM token counts and timing for the reviewer
 * call. Null when the reviewer was not invoked (e.g. execution failed before reaching review).
 */
public record ReviewResult(
        boolean approved,
        List<String> violations,
        String summary,
        PhaseStats phaseStats   // token/timing telemetry for this reviewer call; may be null
) {
    /** Backward-compat constructor for callers that don't have phaseStats. */
    public ReviewResult(boolean approved, List<String> violations, String summary) {
        this(approved, violations, summary, null);
    }
}
