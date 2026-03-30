package com.gitsolve.model;

/**
 * Lifecycle status for an issue record tracked by the persistence layer.
 * Transitions: PENDING → IN_PROGRESS → SUCCESS | FAILED | SKIPPED
 * Execution sub-states (on records that have fix_instructions):
 *   SUCCESS → EXECUTING → PR_SUBMITTED | EXECUTION_FAILED
 */
public enum IssueStatus {
    PENDING,
    IN_PROGRESS,
    SUCCESS,
    FAILED,
    SKIPPED,
    /** Execution agent is actively working on this issue. */
    EXECUTING,
    /** A GitHub PR was successfully submitted. */
    PR_SUBMITTED,
    /** Execution agent failed to submit a PR. */
    EXECUTION_FAILED
}
