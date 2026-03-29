package com.gitsolve.model;

/**
 * Represents a GitHub repository discovered by the Scout Agent.
 * Pure domain record — no framework dependencies.
 */
public record GitRepository(
        String fullName,       // "apache/commons-lang"
        String cloneUrl,
        String htmlUrl,
        int starCount,
        int forkCount,
        int commitCount,       // commits in last 90 days
        double velocityScore   // computed by VelocityScoreCalculator
) {}
