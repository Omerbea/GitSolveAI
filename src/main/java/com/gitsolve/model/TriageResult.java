package com.gitsolve.model;

/**
 * Output of the Triage Agent for a single GitHub issue.
 * Pure domain record — no framework dependencies.
 */
public record TriageResult(
        GitIssue issue,
        IssueComplexity complexity,
        String reasoning,
        boolean accepted       // false if rejected by deterministic rules or LLM classification
) {}
