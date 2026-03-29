package com.gitsolve.model;

/**
 * Represents a single iteration of the SWE Agent's fix loop.
 * Pure domain record — no framework dependencies.
 */
public record FixAttempt(
        int iterationNumber,
        String modifiedFilePath,
        String patchDiff,
        String buildOutput,
        boolean buildPassed
) {}
