package com.gitsolve.model;

/**
 * Lifecycle status for an issue record tracked by the persistence layer.
 * Transitions: PENDING → IN_PROGRESS → SUCCESS | FAILED | SKIPPED
 */
public enum IssueStatus {
    PENDING,
    IN_PROGRESS,
    SUCCESS,
    FAILED,
    SKIPPED
}
