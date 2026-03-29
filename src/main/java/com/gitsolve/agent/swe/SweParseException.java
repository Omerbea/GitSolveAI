package com.gitsolve.agent.swe;

/**
 * Checked exception for SWE Agent parse operations.
 * Signals failures when deserialising the LLM's JSON fix response.
 */
public class SweParseException extends Exception {

    public SweParseException(String message) {
        super(message);
    }

    public SweParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
