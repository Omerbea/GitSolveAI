package com.gitsolve.telemetry;

import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Central observability facade for GitSolve AI agents and orchestrator.
 *
 * All six metrics are registered eagerly in the constructor so they appear
 * in /actuator/prometheus even before any agent runs.
 *
 * Callers:
 *   - FixLoopOrchestrator: recordIssueAttempted, recordIssueSucceeded, recordIssueFailed, startTimer, stopTimer
 *   - Agent services: recordTokensUsed, recordIterationCount
 */
@Component
public class AgentMetrics {

    private static final String PREFIX = "gitsolve_";

    private final MeterRegistry registry;

    // Pre-registered metric references for the no-tag variants
    private final Counter issueSucceededCounter;
    private final DistributionSummary fixIterationsHistogram;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;

        // Eagerly register no-tag metrics so they appear in /actuator/prometheus immediately
        this.issueSucceededCounter = Counter.builder(PREFIX + "issues_succeeded_total")
                .description("Total number of issues successfully fixed and reviewed")
                .register(registry);

        this.fixIterationsHistogram = DistributionSummary.builder(PREFIX + "fix_iterations")
                .description("Number of SWE Agent iterations required per fix attempt")
                .register(registry);

        // Eagerly register tagged metrics with a placeholder tag value
        // so the metric names appear in Prometheus before real calls
        Counter.builder(PREFIX + "issues_attempted_total")
                .description("Total issues attempted by an agent")
                .tag("agent", "none")
                .register(registry);

        Counter.builder(PREFIX + "issues_failed_total")
                .description("Total issues that failed processing")
                .tag("reason", "none")
                .register(registry);

        Counter.builder(PREFIX + "tokens_used_total")
                .description("Total LLM tokens consumed")
                .tags("agent", "none", "model", "none")
                .register(registry);

        Timer.builder(PREFIX + "agent_duration_seconds")
                .description("Time spent in agent operations")
                .tags("operation", "none", "success", "false")
                .register(registry);
    }

    // ------------------------------------------------------------------ //
    // Public API                                                           //
    // ------------------------------------------------------------------ //

    /** Called once per issue before the fix loop begins. */
    public void recordIssueAttempted(String agentName) {
        Counter.builder(PREFIX + "issues_attempted_total")
                .description("Total issues attempted by an agent")
                .tag("agent", agentName)
                .register(registry)
                .increment();
    }

    /** Called when a fix passes review and is marked SUCCESS. */
    public void recordIssueSucceeded() {
        issueSucceededCounter.increment();
    }

    /** Called when a fix fails at any stage (build, review, parse, etc.). */
    public void recordIssueFailed(String reason) {
        Counter.builder(PREFIX + "issues_failed_total")
                .description("Total issues that failed processing")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    /** Called after each LLM call to track token consumption. */
    public void recordTokensUsed(String agent, String model, int tokens) {
        Counter.builder(PREFIX + "tokens_used_total")
                .description("Total LLM tokens consumed")
                .tags("agent", agent, "model", model)
                .register(registry)
                .increment(tokens);
    }

    /** Called at the end of each fix attempt with the total iteration count. */
    public void recordIterationCount(int count) {
        fixIterationsHistogram.record(count);
    }

    /**
     * Starts a timer sample. Caller must hold the sample and pass it to {@link #stopTimer}.
     * Timer samples are lightweight (a nanoTime snapshot).
     */
    public Timer.Sample startTimer(String operation) {
        return Timer.start(registry);
    }

    /**
     * Stops the timer sample and records the elapsed duration.
     *
     * @param sample    The sample returned from {@link #startTimer}
     * @param operation The operation name (used as a tag)
     * @param success   Whether the operation succeeded
     */
    public void stopTimer(Timer.Sample sample, String operation, boolean success) {
        sample.stop(Timer.builder(PREFIX + "agent_duration_seconds")
                .description("Time spent in agent operations")
                .tags("operation", operation, "success", String.valueOf(success))
                .register(registry));
    }
}
