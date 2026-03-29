package com.gitsolve.model;

import java.util.List;

/**
 * Represents a GitHub issue fetched from a repository.
 * Pure domain record — no framework dependencies.
 */
public record GitIssue(
        String repoFullName,
        int issueNumber,
        String title,
        String body,
        String htmlUrl,
        List<String> labels
) {}
