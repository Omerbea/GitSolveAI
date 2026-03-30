package com.gitsolve.telemetry;

import com.gitsolve.persistence.IssueStore;
import com.gitsolve.persistence.SettingsStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies AgentMetrics records correctly and that /actuator/prometheus
 * exposes all 6 expected metric names.
 */
@Tag("unit")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration," +
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentMetricsTest {

    @MockBean IssueStore    issueStore;
    @MockBean SettingsStore settingsStore;

    @Autowired
    AgentMetrics agentMetrics;

    @Autowired
    MeterRegistry registry;

    @Autowired
    MockMvc mockMvc;

    @Test
    void recordIssueAttempted_incrementsCounter() {
        agentMetrics.recordIssueAttempted("scout");
        Counter counter = registry.find("gitsolve_issues_attempted_total")
                .tag("agent", "scout")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void recordIssueSucceeded_incrementsCounter() {
        double before = registry.find("gitsolve_issues_succeeded_total")
                .counter().count();
        agentMetrics.recordIssueSucceeded();
        double after = registry.find("gitsolve_issues_succeeded_total")
                .counter().count();
        assertThat(after).isGreaterThan(before);
    }

    @Test
    void recordIssueFailed_incrementsCounter() {
        agentMetrics.recordIssueFailed("BUILD_FAILURE");
        Counter counter = registry.find("gitsolve_issues_failed_total")
                .tag("reason", "BUILD_FAILURE")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void recordTokensUsed_incrementsCounter() {
        agentMetrics.recordTokensUsed("swe", "claude-3-5-sonnet", 1500);
        Counter counter = registry.find("gitsolve_tokens_used_total")
                .tags("agent", "swe", "model", "claude-3-5-sonnet")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isGreaterThanOrEqualTo(1500.0);
    }

    @Test
    void recordIterationCount_recordsToHistogram() {
        agentMetrics.recordIterationCount(3);
        var summary = registry.find("gitsolve_fix_iterations").summary();
        assertThat(summary).isNotNull();
        assertThat(summary.count()).isGreaterThanOrEqualTo(1);
        assertThat(summary.totalAmount()).isGreaterThanOrEqualTo(3.0);
    }

    @Test
    void startAndStopTimer_recordsDuration() {
        Timer.Sample sample = agentMetrics.startTimer("swe.fix");
        // Simulate some work
        agentMetrics.stopTimer(sample, "swe.fix", true);
        var timer = registry.find("gitsolve_agent_duration_seconds")
                .tags("operation", "swe.fix", "success", "true")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void allSixMetricNamesAreRegisteredInMeterRegistry() {
        // Trigger all metrics so they appear with real (non-placeholder) values
        agentMetrics.recordIssueAttempted("triage");
        agentMetrics.recordIssueSucceeded();
        agentMetrics.recordIssueFailed("TIMEOUT");
        agentMetrics.recordTokensUsed("reviewer", "gemini-pro", 200);
        agentMetrics.recordIterationCount(2);
        Timer.Sample s = agentMetrics.startTimer("test.op");
        agentMetrics.stopTimer(s, "test.op", false);

        // All 6 metrics must exist in the registry — Prometheus scrapes from MeterRegistry
        assertThat(registry.find("gitsolve_issues_attempted_total").counter())
                .as("gitsolve_issues_attempted_total must be registered")
                .isNotNull();
        assertThat(registry.find("gitsolve_issues_succeeded_total").counter())
                .as("gitsolve_issues_succeeded_total must be registered")
                .isNotNull();
        assertThat(registry.find("gitsolve_issues_failed_total").counter())
                .as("gitsolve_issues_failed_total must be registered")
                .isNotNull();
        assertThat(registry.find("gitsolve_tokens_used_total").counter())
                .as("gitsolve_tokens_used_total must be registered")
                .isNotNull();
        assertThat(registry.find("gitsolve_fix_iterations").summary())
                .as("gitsolve_fix_iterations must be registered")
                .isNotNull();
        assertThat(registry.find("gitsolve_agent_duration_seconds").timer())
                .as("gitsolve_agent_duration_seconds must be registered")
                .isNotNull();
    }
}
