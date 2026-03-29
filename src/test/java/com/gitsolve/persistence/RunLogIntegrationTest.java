package com.gitsolve.persistence;

import com.gitsolve.PostgresTestSupport;
import com.gitsolve.model.RunStatus;
import com.gitsolve.persistence.entity.RunLog;
import com.gitsolve.persistence.entity.TokenUsageRecord;
import com.gitsolve.persistence.repository.TokenUsageRecordRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for RunLog and TokenUsage persistence via IssueStore.
 * Uses a real Testcontainers Postgres with Flyway migrations applied.
 */
@Tag("integration")
@Tag("persistence")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("integration-test")
class RunLogIntegrationTest extends PostgresTestSupport {

    @Autowired
    IssueStore issueStore;

    @Autowired
    TokenUsageRecordRepository tokenUsageRepository;

    // ------------------------------------------------------------------ //
    // TC-01: startRun creates a RUNNING RunLog                            //
    // ------------------------------------------------------------------ //

    @Test
    void startRun_createsRunningRunLog() {
        RunLog run = issueStore.startRun();

        assertThat(run.getId()).isNotNull();
        assertThat(run.getRunId()).isNotNull();
        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.getStartedAt()).isNotNull();
        assertThat(run.getFinishedAt()).isNull();
    }

    // ------------------------------------------------------------------ //
    // TC-02: recordTokenUsage persists row retrievable by runId           //
    // ------------------------------------------------------------------ //

    @Test
    void recordTokenUsage_persistsRowRetrievableByRunId() {
        RunLog run = issueStore.startRun();

        issueStore.recordTokenUsage(run.getRunId(), "swe", "claude-3-5-sonnet", 150, 300);

        List<TokenUsageRecord> rows = tokenUsageRepository.findByRunId(run.getRunId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAgentName()).isEqualTo("swe");
        assertThat(rows.get(0).getInputTokens()).isEqualTo(150);
        assertThat(rows.get(0).getOutputTokens()).isEqualTo(300);
        assertThat(rows.get(0).getModel()).isEqualTo("claude-3-5-sonnet");
        assertThat(rows.get(0).getRecordedAt()).isNotNull();
    }

    // ------------------------------------------------------------------ //
    // TC-03: finishRun transitions to COMPLETED with correct counts       //
    // ------------------------------------------------------------------ //

    @Test
    void finishRun_transitionsToCompletedWithCounts() {
        RunLog run = issueStore.startRun();

        issueStore.finishRun(run.getId(), 5, 3, 2, 1, 2000, RunStatus.COMPLETED);

        List<RunLog> recent = issueStore.recentRuns(1);
        assertThat(recent).isNotEmpty();
        RunLog finished = recent.get(0);
        assertThat(finished.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(finished.getIssuesScouted()).isEqualTo(5);
        assertThat(finished.getIssuesTriaged()).isEqualTo(3);
        assertThat(finished.getIssuesAttempted()).isEqualTo(2);
        assertThat(finished.getIssuesSucceeded()).isEqualTo(1);
        assertThat(finished.getTokenUsage()).isEqualTo(2000);
        assertThat(finished.getFinishedAt()).isNotNull();
    }

    // ------------------------------------------------------------------ //
    // TC-04: finishRun with FAILED status                                 //
    // ------------------------------------------------------------------ //

    @Test
    void finishRun_failedStatus_persistsCorrectly() {
        RunLog run = issueStore.startRun();

        issueStore.finishRun(run.getId(), 0, 0, 0, 0, 0, RunStatus.FAILED);

        List<RunLog> recent = issueStore.recentRuns(5);
        RunLog failed = recent.stream()
                .filter(r -> r.getId().equals(run.getId()))
                .findFirst().orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(RunStatus.FAILED);
    }

    // ------------------------------------------------------------------ //
    // TC-05: finishRun throws for unknown id                              //
    // ------------------------------------------------------------------ //

    @Test
    void finishRun_unknownId_throwsIllegalArgument() {
        assertThatThrownBy(() ->
                issueStore.finishRun(Long.MAX_VALUE, 0, 0, 0, 0, 0, RunStatus.COMPLETED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RunLog not found");
    }
}
