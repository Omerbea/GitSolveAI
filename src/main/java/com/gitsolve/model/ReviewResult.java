package com.gitsolve.model;

import java.util.List;

/**
 * Output of the Reviewer Agent's validation of a fix against governance constraints.
 * Pure domain record — no framework dependencies.
 */
public record ReviewResult(
        boolean approved,
        List<String> violations,
        String summary
) {}
