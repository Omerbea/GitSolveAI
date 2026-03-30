package com.gitsolve.model;

/**
 * Telemetry captured for a single agent phase (Analysis, FileSelection, Execution, Reviewer).
 *
 * <p>Token counts default to 0 when the provider does not return usage metadata.
 * {@code modelName} is the exact model identifier reported by the response or derived from
 * the AgentConfig property used to build the ChatLanguageModel bean.
 *
 * <p>T02 will thread PhaseStats through domain models (FixReport, FixAttempt) so the
 * dashboard can render the four-card telemetry panel.
 */
public record PhaseStats(
        int    inputTokens,   // tokens consumed by the input prompt; 0 if unavailable
        int    outputTokens,  // tokens produced in the response; 0 if unavailable
        String modelName,     // e.g. "claude-haiku-4-5-20251001"
        long   durationMs,    // wall-clock duration of the LLM call in milliseconds
        String phaseDetail    // phase-specific supplementary info (e.g. complexity, file count)
) {
    /** Convenience factory for a zero-stat placeholder (used before the real call completes). */
    public static PhaseStats empty(String modelName) {
        return new PhaseStats(0, 0, modelName, 0, "");
    }
}
