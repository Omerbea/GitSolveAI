package com.gitsolve.agent.execution;

/**
 * Thrown by {@link ExecutionFixParser} when the LLM response cannot be parsed
 * into a valid {@link ExecutionFixResponse}.
 */
public class ExecutionParseException extends RuntimeException {

    public ExecutionParseException(String message) {
        super(message);
    }

    public ExecutionParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
