package com.gitsolve.agent.execution;

/**
 * Named phases of a single execution run.
 * Order reflects the actual pipeline sequence.
 */
public enum ExecutionStep {
    FORK,
    CLONE,
    DOCKER_SETUP,
    BRANCH,
    LLM_CALL,
    APPLY_FILES,
    BUILD,
    PUSH,
    PR_OPEN
}
