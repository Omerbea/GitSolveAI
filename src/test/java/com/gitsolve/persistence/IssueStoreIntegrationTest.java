package com.gitsolve.persistence;

import com.gitsolve.PostgresTestSupport;
import com.gitsolve.model.*;
import com.gitsolve.persistence.entity.IssueRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for IssueStore using a real Testcontainers Postgres.
 * Flyway migrations are applied automatically via the integration-test profile.
 */
@Tag("integration")
@Tag("persistence")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("integration-test")
class IssueStoreIntegrationTest extends PostgresTestSupport {

    @Autowired
    IssueStore issueStore;

    // ------------------------------------------------------------------ //
    // Helpers                                                              //
    // ------------------------------------------------------------------ //

    private GitIssue sampleIssue(int issueNumber) {
        return new GitIssue(
                "apache/commons-lang",
                issueNumber,
                "Fix NPE in StringUtils.isBlank",
                "When passing null to StringUtils.isBlank it throws NPE instead of returning true.",
                "https://github.com/apache/commons-lang/issues/" + issueNumber,
                List.of("bug", "good-first-issue")
        );
    }

    // ------------------------------------------------------------------ //
    // Tests                                                                //
    // ------------------------------------------------------------------ //

    @Test
    void createPending_setsStatusToPending() {
        IssueRecord record = issueStore.createPending(sampleIssue(1001));

        assertThat(record.getId()).isNotNull();
        assertThat(record.getStatus()).isEqualTo(IssueStatus.PENDING);
        assertThat(record.getIterationCount()).isEqualTo(0);
        assertThat(record.getRepoFullName()).isEqualTo("apache/commons-lang");
        assertThat(record.getIssueNumber()).isEqualTo(1001);
    }

    @Test
    void lifecyclePendingToInProgressToSuccess() {
        IssueRecord record = issueStore.createPending(sampleIssue(1002));
        Long id = record.getId();

        issueStore.markInProgress(id);
        IssueRecord afterInProgress = issueStore.findExisting(
                "https://github.com/apache/commons-lang", 1002).orElseThrow();
        assertThat(afterInProgress.getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);

        issueStore.markSuccess(id, "diff --git a/Foo.java...", "Fixed NPE in isBlank", 2);
        IssueRecord afterSuccess = issueStore.findExisting(
                "https://github.com/apache/commons-lang", 1002).orElseThrow();
        assertThat(afterSuccess.getStatus()).isEqualTo(IssueStatus.SUCCESS);
        assertThat(afterSuccess.getFixDiff()).startsWith("diff --git");
        assertThat(afterSuccess.getFixSummary()).isEqualTo("Fixed NPE in isBlank");
        assertThat(afterSuccess.getIterationCount()).isEqualTo(2);
        assertThat(afterSuccess.getCompletedAt()).isNotNull();
    }

    @Test
    void markFailed_setsReasonAndIterations() {
        IssueRecord record = issueStore.createPending(sampleIssue(1003));
        Long id = record.getId();

        issueStore.markInProgress(id);
        issueStore.markFailed(id, "BUILD_FAILED: compilation error", 5);

        IssueRecord failed = issueStore.findExisting(
                "https://github.com/apache/commons-lang", 1003).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(IssueStatus.FAILED);
        assertThat(failed.getFailureReason()).isEqualTo("BUILD_FAILED: compilation error");
        assertThat(failed.getIterationCount()).isEqualTo(5);
    }

    @Test
    void uniqueConstraint_rejectsduplicateIssue() {
        issueStore.createPending(sampleIssue(1004));

        assertThatThrownBy(() -> issueStore.createPending(sampleIssue(1004)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findByStatus_returnsOnlyMatchingRecords() {
        issueStore.createPending(sampleIssue(1005));
        IssueRecord toSucceed = issueStore.createPending(sampleIssue(1006));
        issueStore.markInProgress(toSucceed.getId());
        issueStore.markSuccess(toSucceed.getId(), "--- patch", "summary", 1);

        List<IssueRecord> successList = issueStore.findByStatus(IssueStatus.SUCCESS);
        assertThat(successList).anyMatch(r -> r.getIssueNumber().equals(1006));
        assertThat(successList).noneMatch(r -> r.getIssueNumber().equals(1005));
    }

    @Test
    void currentRunStats_countsCorrectly() {
        issueStore.createPending(sampleIssue(2001));
        issueStore.createPending(sampleIssue(2002));
        IssueRecord toFail = issueStore.createPending(sampleIssue(2003));
        issueStore.markInProgress(toFail.getId());
        issueStore.markFailed(toFail.getId(), "TIMEOUT", 3);

        RunStats stats = issueStore.currentRunStats();
        assertThat(stats.pending()).isGreaterThanOrEqualTo(2);
        assertThat(stats.failed()).isGreaterThanOrEqualTo(1);
        assertThat(stats.total()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void saveConstraintJson_persistsAndRetrievesCorrectly() {
        IssueRecord record = issueStore.createPending(sampleIssue(1007));
        ConstraintJson constraints = new ConstraintJson(
                "checkstyle.xml",
                false,
                true,
                "21",
                "mvn test",
                List.of("System.out.println")
        );

        issueStore.saveConstraintJson(record.getId(), constraints);

        IssueRecord reloaded = issueStore.findExisting(
                "https://github.com/apache/commons-lang", 1007).orElseThrow();
        ConstraintJson loaded = reloaded.getConstraintJson();
        assertThat(loaded).isNotNull();
        assertThat(loaded.checkstyleConfig()).isEqualTo("checkstyle.xml");
        assertThat(loaded.requiresTests()).isTrue();
        assertThat(loaded.jdkVersion()).isEqualTo("21");
        assertThat(loaded.forbiddenPatterns()).containsExactly("System.out.println");
    }

    @Test
    void findOrThrow_throwsIllegalArgumentForUnknownId() {
        assertThatThrownBy(() -> issueStore.markInProgress(Long.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IssueRecord not found");
    }
}
