package com.gitsolve.docker;

/**
 * Checked exception for Docker build environment operations.
 * Signals failures in container lifecycle, file I/O, or build execution.
 */
public class BuildEnvironmentException extends Exception {

    public BuildEnvironmentException(String message) {
        super(message);
    }

    public BuildEnvironmentException(String message, Throwable cause) {
        super(message, cause);
    }
}
