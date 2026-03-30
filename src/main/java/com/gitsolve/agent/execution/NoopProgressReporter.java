package com.gitsolve.agent.execution;

/** No-op reporter — used in tests and as a safe default when no SSE client is connected. */
public class NoopProgressReporter implements ExecutionProgressReporter {

    public static final NoopProgressReporter INSTANCE = new NoopProgressReporter();

    @Override
    public void step(long recordId, ExecutionStep step, StepStatus status, String detail, int iteration) {
        // intentionally empty
    }
}
