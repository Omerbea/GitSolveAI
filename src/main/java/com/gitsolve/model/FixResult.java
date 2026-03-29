package com.gitsolve.model;

import java.util.List;

/**
 * Final result of the SWE Agent's attempt to fix a single issue.
 * Pure domain record — no framework dependencies.
 */
public record FixResult(
        String issueId,
        boolean success,
        List<FixAttempt> attempts,
        String finalDiff,
        String failureReason    // null if success == true
) {}
