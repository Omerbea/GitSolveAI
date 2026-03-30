package com.gitsolve.dashboard;

import com.gitsolve.agent.execution.ExecutionStep;
import com.gitsolve.agent.execution.StepStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Tag("unit")
class SseExecutionProgressReporterTest {

    @Test
    void step_broadcastsExecutionStepEvent() {
        SseEmitterRegistry registry = mock(SseEmitterRegistry.class);
        SseExecutionProgressReporter reporter = new SseExecutionProgressReporter(registry);

        reporter.step(42L, ExecutionStep.BUILD, StepStatus.RUNNING, "Iteration 1/3", 1);

        ArgumentCaptor<String> eventName = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload   = ArgumentCaptor.forClass(String.class);
        verify(registry).broadcast(eventName.capture(), payload.capture());

        assertThat(eventName.getValue()).isEqualTo("execution-step");
        assertThat(payload.getValue()).contains("\"recordId\":42");
        assertThat(payload.getValue()).contains("\"step\":\"BUILD\"");
        assertThat(payload.getValue()).contains("\"status\":\"RUNNING\"");
        assertThat(payload.getValue()).contains("\"iteration\":1");
        assertThat(payload.getValue()).contains("\"detail\":\"Iteration 1/3\"");
    }

    @Test
    void step_nullDetail_serialisesAsNull() {
        SseEmitterRegistry registry = mock(SseEmitterRegistry.class);
        SseExecutionProgressReporter reporter = new SseExecutionProgressReporter(registry);

        reporter.step(7L, ExecutionStep.FORK, StepStatus.DONE);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(registry).broadcast(eq("execution-step"), payload.capture());

        assertThat(payload.getValue()).contains("\"detail\":null");
        assertThat(payload.getValue()).contains("\"iteration\":0");
    }

    @Test
    void buildJson_escapesSpecialChars() {
        String json = SseExecutionProgressReporter.buildJson(
                1L, ExecutionStep.PR_OPEN, StepStatus.DONE,
                "PR: \"title\" with\nnewline", 0);

        assertThat(json).contains("\\\"title\\\"");
        assertThat(json).contains("\\n");
    }
}
