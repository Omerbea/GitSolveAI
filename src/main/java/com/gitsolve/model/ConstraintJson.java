package com.gitsolve.model;

import java.util.List;

/**
 * Structured governance constraints extracted from a repository's CONTRIBUTING.md
 * by the RuleExtractor. Used by the Reviewer Agent and SWE Agent.
 * Pure domain record — no framework dependencies.
 */
public record ConstraintJson(
        String checkstyleConfig,         // path to checkstyle XML, or null
        boolean requiresDco,             // Signed-off-by required
        boolean requiresTests,           // tests required with every fix
        String jdkVersion,               // "21", "17", "11", or "unknown"
        String buildCommand,             // e.g. "mvn test", "gradle test"
        List<String> forbiddenPatterns   // e.g. ["System.out.println"]
) {}
