package com.gitsolve.repocache;

/**
 * Thrown when a git operation inside RepoCache fails (non-zero exit or process error).
 */
public class RepoCacheException extends Exception {

    public RepoCacheException(String message) {
        super(message);
    }

    public RepoCacheException(String message, Throwable cause) {
        super(message, cause);
    }
}
