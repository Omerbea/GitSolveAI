package com.gitsolve.dashboard;

import com.gitsolve.agent.execution.ExecutionProgressReporter;
import com.gitsolve.agent.execution.ExecutionStep;
import com.gitsolve.agent.execution.StepStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SSE-backed implementation of {@link ExecutionProgressReporter}.
 *
 * <p>Each {@code step()} call serialises the event to a compact JSON payload and
 * broadcasts it as an {@code execution-step} SSE event to all connected clients.
 *
 * <p>Payload shape:
 * <pre>
 * {
 *   "recordId": 34,
 *   "step":     "BUILD",
 *   "status":   "RUNNING",
 *   "detail":   "Iteration 2/3",   // may be null
 *   "iteration": 2                  // 0 means not applicable
 * }
 * </pre>
 */
public class SseExecutionProgressReporter implements ExecutionProgressReporter {

    private static final Logger log = LoggerFactory.getLogger(SseExecutionProgressReporter.class);

    private final SseEmitterRegistry registry;

    public SseExecutionProgressReporter(SseEmitterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void step(long recordId, ExecutionStep step, StepStatus status, String detail, int iteration) {
        String json = buildJson(recordId, step, status, detail, iteration);
        log.debug("SSE execution-step: {}", json);
        registry.broadcast("execution-step", json);
    }

    static String buildJson(long recordId, ExecutionStep step, StepStatus status,
                             String detail, int iteration) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"recordId\":").append(recordId)
          .append(",\"step\":\"").append(step.name()).append("\"")
          .append(",\"status\":\"").append(status.name()).append("\"")
          .append(",\"iteration\":").append(iteration);
        if (detail != null) {
            sb.append(",\"detail\":\"").append(jsonEscape(detail)).append("\"");
        } else {
            sb.append(",\"detail\":null");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
