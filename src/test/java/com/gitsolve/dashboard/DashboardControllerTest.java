package com.gitsolve.dashboard;

import com.gitsolve.agent.instructions.FixInstructionsService;
import com.gitsolve.config.GitSolveProperties;
import com.gitsolve.dashboard.SseEmitterRegistry;
import com.gitsolve.model.AppSettings;
import com.gitsolve.model.FixReport;
import com.gitsolve.model.IssueStatus;
import com.gitsolve.model.RunStats;
import com.gitsolve.persistence.IssueStore;
import com.gitsolve.persistence.entity.IssueRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * @WebMvcTest for DashboardController — exercises Thymeleaf template rendering
 * via MockMvc with a mocked IssueStore.
 */
@Tag("unit")
@WebMvcTest(DashboardController.class)
@ActiveProfiles("test")
class DashboardControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean IssueStore             issueStore;
    @MockBean GitSolveProperties     props;
    @MockBean FixInstructionsService fixInstructionsService;
    @MockBean SseEmitterRegistry     sseRegistry;
    @MockBean ApplicationContext     applicationContext;

    @BeforeEach
    void setUpProps() {
        GitSolveProperties.GitHub github = new GitSolveProperties.GitHub(
                "token", 2, 5, List.of(), "");
        when(props.github()).thenReturn(github);
        when(issueStore.countAllIssues()).thenReturn(0L);
    }

    // ------------------------------------------------------------------ //
    // TC-01: GET / returns 200 with issue table                           //
    // ------------------------------------------------------------------ //

    @Test
    void getIndex_returns200WithIssueTable() throws Exception {
        IssueRecord success = issueRecord(1L, "Fix NPE in StringUtils", "apache/commons-lang",
                42, IssueStatus.SUCCESS, "--- a/Foo.java\n+++ b/Foo.java", 2);
        IssueRecord failed = issueRecord(2L, "Fix timeout in HttpClient", "apache/httpcomponents",
                7, IssueStatus.FAILED, null, 5);

        when(issueStore.findByStatus(IssueStatus.SUCCESS)).thenReturn(List.of(success));
        when(issueStore.findByStatus(IssueStatus.FAILED)).thenReturn(List.of(failed));
        when(issueStore.currentRunStats()).thenReturn(new RunStats(0, 0, 1, 1, 0));
        when(issueStore.recentRuns(anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Fix NPE in StringUtils")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Fix timeout in HttpClient")));
    }

    // ------------------------------------------------------------------ //
    // TC-02: GET /issues/{id}/diff returns diff content                   //
    // ------------------------------------------------------------------ //

    @Test
    void getDiff_successRecord_returnsDiffContent() throws Exception {
        IssueRecord record = issueRecord(1L, "Fix NPE", "apache/commons-lang",
                42, IssueStatus.SUCCESS, "--- a/Foo.java\n+++ b/Foo.java\n+fixed", 2);
        when(issueStore.findById(1L)).thenReturn(Optional.of(record));

        mockMvc.perform(get("/issues/1/diff"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("--- a/Foo.java")));
    }

    // ------------------------------------------------------------------ //
    // TC-03: null fixDiff → "No diff available"                           //
    // ------------------------------------------------------------------ //

    @Test
    void getDiff_nullDiff_returnsNoDiffAvailable() throws Exception {
        IssueRecord record = issueRecord(2L, "Fix timeout", "apache/httpcomponents",
                7, IssueStatus.FAILED, null, 5);
        when(issueStore.findById(2L)).thenReturn(Optional.of(record));

        mockMvc.perform(get("/issues/2/diff"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No diff available")));
    }

    // ------------------------------------------------------------------ //
    // TC-04: unknown id → 404                                             //
    // ------------------------------------------------------------------ //

    @Test
    void getDiff_unknownId_returns404() throws Exception {
        when(issueStore.findById(9999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/issues/9999/diff"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ //
    // TC-05: GET /issues/{id}/report returns report content               //
    // ------------------------------------------------------------------ //

    @Test
    void getReport_returnsReportContent() throws Exception {
        IssueRecord record = issueRecord(3L, "Fix NPE in StringUtils", "apache/commons-lang",
                99, IssueStatus.FAILED, null, 3);
        record.setFixReport(new FixReport(
                "Fix NPE in StringUtils",
                "https://github.com/apache/commons-lang/issues/99",
                "When passing null it throws NPE.",
                "Root cause: missing null check in isEmpty()",
                "src/main/java/StringUtils.java",
                "",
                "--- a/StringUtils.java\n+++ b/StringUtils.java\n+if (str == null) return true;",
                "FAILED",
                3,
                List.of("Iteration 1: ❌ BUILD FAILED", "Iteration 2: ❌ BUILD FAILED", "Iteration 3: ❌ BUILD FAILED"),
                Instant.now()
        ));
        when(issueStore.findById(3L)).thenReturn(Optional.of(record));

        mockMvc.perform(get("/issues/3/report"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Fix NPE in StringUtils")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Root cause")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("StringUtils.java")));
    }

    // ------------------------------------------------------------------ //
    // TC-06: GET /issues/{id}/report unknown id → 404                     //
    // ------------------------------------------------------------------ //

    @Test
    void getReport_unknownId_returns404() throws Exception {
        when(issueStore.findById(9999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/issues/9999/report"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ //
    // TC-07: POST /issues/{id}/execute — 202 when instructions present   //
    // ------------------------------------------------------------------ //

    @Test
    void executeEndpoint_withFixInstructions_returns202() throws Exception {
        IssueRecord record = issueRecord(10L, "Fix NPE", "apache/commons-lang",
                42, IssueStatus.SUCCESS, null, 0);
        record.setFixInstructions("Do the fix by changing Foo.java");
        when(issueStore.findById(10L)).thenReturn(Optional.of(record));
        doNothing().when(issueStore).markExecuting(anyLong());

        mockMvc.perform(post("/issues/10/execute"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("started"))
                .andExpect(jsonPath("$.recordId").value(10));
    }

    // ------------------------------------------------------------------ //
    // TC-08: POST /issues/{id}/execute — 400 when no instructions        //
    // ------------------------------------------------------------------ //

    @Test
    void executeEndpoint_noFixInstructions_returns400() throws Exception {
        IssueRecord record = issueRecord(11L, "Fix NPE", "apache/commons-lang",
                42, IssueStatus.SUCCESS, null, 0);
        // fixInstructions is null by default
        when(issueStore.findById(11L)).thenReturn(Optional.of(record));

        mockMvc.perform(post("/issues/11/execute"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    // ------------------------------------------------------------------ //
    // TC-09: POST /issues/{id}/execute — 409 when PR already submitted   //
    // ------------------------------------------------------------------ //

    @Test
    void executeEndpoint_prAlreadySet_returns409() throws Exception {
        IssueRecord record = issueRecord(12L, "Fix NPE", "apache/commons-lang",
                42, IssueStatus.SUCCESS, null, 0);
        record.setFixInstructions("Some instructions");
        record.setPrUrl("https://github.com/apache/commons-lang/pull/99");
        when(issueStore.findById(12L)).thenReturn(Optional.of(record));

        mockMvc.perform(post("/issues/12/execute"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.prUrl").value("https://github.com/apache/commons-lang/pull/99"));
    }

    // ------------------------------------------------------------------ //
    // TC-10: report page renders Execute button when instructions present //
    // ------------------------------------------------------------------ //

    @Test
    void reportPage_rendersExecuteButton_whenInstructionsPresent() throws Exception {
        IssueRecord record = issueRecord(13L, "Fix NPE", "apache/commons-lang",
                42, IssueStatus.SUCCESS, null, 0);
        record.setFixReport(minimalFixReport("Fix NPE"));
        record.setFixInstructions("Do the fix by changing Foo.java");
        // prUrl is null — execute button should appear
        when(issueStore.findById(13L)).thenReturn(Optional.of(record));

        mockMvc.perform(get("/issues/13/report"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Execute")));
    }

    // ------------------------------------------------------------------ //
    // TC-11: report page renders PR link when prUrl set                   //
    // ------------------------------------------------------------------ //

    @Test
    void reportPage_rendersPrLink_whenPrUrlSet() throws Exception {
        IssueRecord record = issueRecord(14L, "Fix NPE", "apache/commons-lang",
                42, IssueStatus.SUCCESS, null, 0);
        record.setFixReport(minimalFixReport("Fix NPE"));
        record.setPrUrl("https://github.com/apache/commons-lang/pull/99");
        when(issueStore.findById(14L)).thenReturn(Optional.of(record));

        mockMvc.perform(get("/issues/14/report"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers
                        .containsString("https://github.com/apache/commons-lang/pull/99")));
    }

    // ------------------------------------------------------------------ //
    // Helper                                                               //
    // ------------------------------------------------------------------ //

    private static IssueRecord issueRecord(Long id, String title, String repoFullName,
                                            int issueNumber, IssueStatus status,
                                            String fixDiff, int iterations) {
        IssueRecord r = new IssueRecord();
        r.setId(id);
        r.setIssueTitle(title);
        r.setRepoFullName(repoFullName);
        r.setIssueNumber(issueNumber);
        r.setStatus(status);
        r.setFixDiff(fixDiff);
        r.setIterationCount(iterations);
        return r;
    }

    private static FixReport minimalFixReport(String title) {
        return new FixReport(title, "https://github.com/owner/repo/issues/1",
                "body", "root cause", "Foo.java", "", "", "ANALYSED", 0,
                List.of("Complexity: EASY"), Instant.now());
    }
}
