package com.gitsolve.agent.reporter;

import com.gitsolve.model.FixAttempt;
import com.gitsolve.model.FixReport;
import com.gitsolve.model.FixResult;
import com.gitsolve.model.GitIssue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ReporterService — no Spring context, no mocks needed (pure logic).
 */
@Tag("unit")
class ReporterServiceTest {

    private ReporterService service;

    private static final GitIssue ISSUE = new GitIssue(
            "apache/commons-lang", 99,
            "Fix null handling in StringUtils",
            "When passing null to StringUtils.isEmpty it throws NPE instead of returning true. " +
            "Steps to reproduce: call StringUtils.isEmpty(null). Expected: true. Actual: NPE.",
            "https://github.com/apache/commons-lang/issues/99",
            List.of("good first issue", "bug")
    );

    @BeforeEach
    void setUp() {
        service = new ReporterService();
    }

    // ------------------------------------------------------------------ //
    // TC-01: Successful fix produces PASSED report                        //
    // ------------------------------------------------------------------ //

    @Test
    void generateReport_successFix_returnsPASSEDReport() {
        FixResult fix = new FixResult(
                "apache/commons-lang#99", true,
                List.of(
                        new FixAttempt(1, "src/main/java/StringUtils.java",
                                "--- a/StringUtils.java\n+++ b/StringUtils.java",
                                "BUILD SUCCESS", false),
                        new FixAttempt(2, "src/main/java/StringUtils.java",
                                "--- a/StringUtils.java\n+++ b/StringUtils.java",
                                "BUILD SUCCESS", true)
                ),
                "--- a/StringUtils.java\n+++ b/StringUtils.java\n+null check added",
                null
        );

        FixReport report = service.generateReport(fix, ISSUE);

        assertThat(report).isNotNull();
        assertThat(report.buildStatus()).isEqualTo("PASSED");
        assertThat(report.iterationCount()).isEqualTo(2);
        assertThat(report.iterationHistory()).hasSize(2);
        assertThat(report.iterationHistory().get(0)).contains("Iteration 1");
        assertThat(report.iterationHistory().get(1)).contains("✅ BUILD PASSED");
        assertThat(report.proposedFilePath()).isEqualTo("src/main/java/StringUtils.java");
        assertThat(report.unifiedDiff()).contains("null check added");
        assertThat(report.issueTitle()).isEqualTo("Fix null handling in StringUtils");
        assertThat(report.issueUrl()).isEqualTo("https://github.com/apache/commons-lang/issues/99");
        assertThat(report.generatedAt()).isNotNull();
    }

    // ------------------------------------------------------------------ //
    // TC-02: Failed fix produces FAILED report                            //
    // ------------------------------------------------------------------ //

    @Test
    void generateReport_failedFix_returnsFAILEDReport() {
        FixResult fix = new FixResult(
                "apache/commons-lang#99", false,
                List.of(
                        new FixAttempt(1, "src/main/java/StringUtils.java", "", "BUILD FAILED", false),
                        new FixAttempt(2, "src/main/java/StringUtils.java", "", "BUILD FAILED", false),
                        new FixAttempt(3, "src/main/java/StringUtils.java", "", "BUILD FAILED", false)
                ),
                "",
                "Exhausted 3 iterations without a passing build"
        );

        FixReport report = service.generateReport(fix, ISSUE);

        assertThat(report.buildStatus()).isEqualTo("FAILED");
        assertThat(report.iterationCount()).isEqualTo(3);
        assertThat(report.iterationHistory()).hasSize(3);
        assertThat(report.iterationHistory().get(0)).contains("❌ BUILD FAILED");
        assertThat(report.rootCauseAnalysis()).contains("Exhausted 3 iterations");
    }

    // ------------------------------------------------------------------ //
    // TC-03: Empty attempts list — no NPE                                 //
    // ------------------------------------------------------------------ //

    @Test
    void generateReport_noAttempts_returnsNotRunReport() {
        FixResult fix = new FixResult(
                "apache/commons-lang#99", false, List.of(), "", "Docker error"
        );

        FixReport report = service.generateReport(fix, ISSUE);

        assertThat(report).isNotNull();
        assertThat(report.buildStatus()).isEqualTo("NOT_RUN");
        assertThat(report.iterationCount()).isEqualTo(0);
        assertThat(report.iterationHistory()).isEmpty();
    }
}
