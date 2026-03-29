package com.gitsolve.model;

/**
 * Aggregate statistics for the current or most recent run.
 * Used by IssueStore.currentRunStats() and the dashboard.
 * Pure domain record — no framework dependencies.
 */
public record RunStats(
        int pending,
        int inProgress,
        int succeeded,
        int failed,
        int skipped
) {
    public int total() {
        return pending + inProgress + succeeded + failed + skipped;
    }
}
