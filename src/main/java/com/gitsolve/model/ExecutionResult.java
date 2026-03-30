package com.gitsolve.model;

/**
 * Result of an ExecutionService run — fork, code application, build, push, and PR creation.
 */
public record ExecutionResult(
        boolean success,
        String  prUrl,          // non-null on success
        String  diff,           // unified diff of all changes; may be empty on failure
        int     iterations,     // number of LLM+build cycles attempted
        String  failureReason   // null on success
) {
    public static ExecutionResult success(String prUrl, String diff, int iterations) {
        return new ExecutionResult(true, prUrl, diff, iterations, null);
    }

    public static ExecutionResult failure(String reason, String diff, int iterations) {
        return new ExecutionResult(false, null, diff, iterations, reason);
    }
}
