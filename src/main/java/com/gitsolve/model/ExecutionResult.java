package com.gitsolve.model;

/**
 * Result of an ExecutionService run — fork, code application, build, push, and PR creation.
 *
 * <p>The {@code phaseStats} field accumulates token counts across all LLM iterations in the
 * execution loop. The {@code reviewerPhaseStats} field carries the reviewer agent's token
 * counts from the final passing-build review call.
 * Both are null when the execution short-circuits before reaching those phases.
 */
public record ExecutionResult(
        boolean success,
        String  prUrl,              // non-null on success
        String  diff,               // unified diff of all changes; may be empty on failure
        int     iterations,         // number of LLM+build cycles attempted
        String  failureReason,      // null on success
        PhaseStats phaseStats,          // accumulated execution LLM telemetry; null if not reached
        PhaseStats reviewerPhaseStats   // reviewer LLM telemetry; null if not reached
) {
    public static ExecutionResult success(String prUrl, String diff, int iterations,
                                          PhaseStats phaseStats, PhaseStats reviewerPhaseStats) {
        return new ExecutionResult(true, prUrl, diff, iterations, null,
                phaseStats, reviewerPhaseStats);
    }

    /** Backward-compat factory for callers that don't have phaseStats (e.g. tests). */
    public static ExecutionResult success(String prUrl, String diff, int iterations) {
        return new ExecutionResult(true, prUrl, diff, iterations, null, null, null);
    }

    public static ExecutionResult failure(String reason, String diff, int iterations) {
        return new ExecutionResult(false, null, diff, iterations, reason, null, null);
    }

    public static ExecutionResult failure(String reason, String diff, int iterations,
                                          PhaseStats phaseStats, PhaseStats reviewerPhaseStats) {
        return new ExecutionResult(false, null, diff, iterations, reason,
                phaseStats, reviewerPhaseStats);
    }
}
