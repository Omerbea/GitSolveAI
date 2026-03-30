package com.gitsolve.agent.execution;

/**
 * Callback interface for reporting execution progress.
 *
 * <p>Called by {@link ExecutionService} at each named phase transition.
 * Implementations push events to SSE clients or collect for testing.
 */
public interface ExecutionProgressReporter {

    /**
     * Reports a step transition with an optional human-readable detail string.
     *
     * @param recordId  the issue_records.id this execution is for
     * @param step      which pipeline phase
     * @param status    new status of that phase
     * @param detail    optional context (e.g. "Iteration 2/3", "apache/dubbo", PR URL)
     * @param iteration optional 1-based iteration counter; 0 means not applicable
     */
    void step(long recordId, ExecutionStep step, StepStatus status, String detail, int iteration);

    /** Convenience overload — no iteration context. */
    default void step(long recordId, ExecutionStep step, StepStatus status, String detail) {
        step(recordId, step, status, detail, 0);
    }

    /** Convenience overload — no detail, no iteration. */
    default void step(long recordId, ExecutionStep step, StepStatus status) {
        step(recordId, step, status, null, 0);
    }
}
