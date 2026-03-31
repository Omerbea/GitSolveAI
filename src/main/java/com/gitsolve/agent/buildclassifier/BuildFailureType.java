package com.gitsolve.agent.buildclassifier;

/**
 * Typed categories of build failure that the {@link BuildFailureClassifier} can emit.
 *
 * <p>{@code LLM_HALLUCINATION} is the safe fallback used when the LLM response cannot
 * be mapped to any recognised failure pattern.
 */
public enum BuildFailureType {
    COMPILE_ERROR,
    TEST_FAILURE,
    DEPENDENCY_RESOLUTION,
    WRONG_BUILD_COMMAND,
    MISSING_FILE,
    LLM_HALLUCINATION
}
