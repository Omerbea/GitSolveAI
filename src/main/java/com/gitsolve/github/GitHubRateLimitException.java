package com.gitsolve.github;

/**
 * Thrown when the GitHub rate limit has been exceeded and all retries are exhausted.
 */
public class GitHubRateLimitException extends RuntimeException {

    private final int retryCount;

    public GitHubRateLimitException(String message, int retryCount) {
        super(message);
        this.retryCount = retryCount;
    }

    public int getRetryCount() {
        return retryCount;
    }
}
