package com.gitsolve.agent.reviewer;

/**
 * Checked exception for Reviewer Agent parse operations.
 * Signals failures when deserialising the LLM's JSON review response.
 */
public class ReviewerParseException extends Exception {

    public ReviewerParseException(String message) {
        super(message);
    }

    public ReviewerParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
