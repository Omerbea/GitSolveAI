package com.gitsolve.dashboard;

import com.gitsolve.model.IssueStatus;
import com.gitsolve.model.RunStats;
import com.gitsolve.persistence.IssueStore;
import com.gitsolve.persistence.entity.IssueRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @MockBean
    IssueStore issueStore;

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
        record.setFixReport(new com.gitsolve.model.FixReport(
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
                java.time.Instant.now()
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
}
