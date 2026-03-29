package com.gitsolve.orchestration;

import com.gitsolve.agent.reviewer.ReviewerService;
import com.gitsolve.agent.scout.ScoutService;
import com.gitsolve.agent.swe.SweService;
import com.gitsolve.agent.triage.TriageService;
import com.gitsolve.config.GitSolveProperties;
import com.gitsolve.model.*;
import com.gitsolve.persistence.IssueStore;
import com.gitsolve.persistence.entity.IssueRecord;
import com.gitsolve.telemetry.AgentMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FixLoopOrchestrator — no Spring context, pure Mockito mocks.
 *
 * SweService is obtained via ApplicationContext.getBean(SweService.class) per issue,
 * so ApplicationContext is mocked to return a fresh mock SweService.
 */
@Tag("unit")
class FixLoopOrchestratorTest {

    // ------------------------------------------------------------------ //
    // Mocks                                                                //
    // ------------------------------------------------------------------ //

    private ScoutService       mockScout;
    private TriageService      mockTriage;
    private SweService         mockSwe;
    private ReviewerService    mockReviewer;
    private com.gitsolve.agent.reporter.ReporterService mockReporter;
    private IssueStore         mockIssueStore;
    private AgentMetrics       mockMetrics;
    private ApplicationContext mockContext;
    private GitSolveProperties mockProps;

    private FixLoopOrchestrator orchestrator;

    // ------------------------------------------------------------------ //
    // Fixtures                                                             //
    // ------------------------------------------------------------------ //

    private static final GitRepository REPO = new GitRepository(
            "apache/commons-lang",
            "https://github.com/apache/commons-lang.git",
            "https://github.com/apache/commons-lang",
            10000, 500, 200, 9.5
    );

    private static final GitIssue ISSUE = new GitIssue(
            "apache/commons-lang", 42,
            "Fix NPE in StringUtils",
            "There is a null dereference in StringUtils.isEmpty when called with a special char.",
            "https://github.com/apache/commons-lang/issues/42",
            List.of("good first issue")
    );

    @BeforeEach
    void setUp() {
        mockScout    = mock(ScoutService.class);
        mockTriage   = mock(TriageService.class);
        mockSwe      = mock(SweService.class);
        mockReviewer = mock(ReviewerService.class);
        mockReporter = mock(com.gitsolve.agent.reporter.ReporterService.class);
        mockIssueStore = mock(IssueStore.class);
        mockMetrics  = mock(AgentMetrics.class);
        mockContext  = mock(ApplicationContext.class);

        // Wire GitSolveProperties mocks
        mockProps = mock(GitSolveProperties.class);
        GitSolveProperties.GitHub github = mock(GitSolveProperties.GitHub.class);
        GitSolveProperties.Llm    llm    = mock(GitSolveProperties.Llm.class);
        when(mockProps.github()).thenReturn(github);
        when(mockProps.llm()).thenReturn(llm);
        when(github.maxReposPerRun()).thenReturn(1);
        when(github.maxIssuesPerRepo()).thenReturn(5);
        when(llm.maxTokensPerRun()).thenReturn(500_000);
        when(llm.maxIterationsPerFix()).thenReturn(5);
        when(llm.model()).thenReturn("claude-3-5-sonnet-20241022");

        orchestrator = new FixLoopOrchestrator(
                mockScout, mockTriage, mockReviewer,
                mockReporter,
                mockIssueStore, mockMetrics, mockProps, mockContext);

        // Stub run lifecycle — new calls added in M007
        com.gitsolve.persistence.entity.RunLog mockRunLog =
                new com.gitsolve.persistence.entity.RunLog();
        mockRunLog.setId(10L);
        mockRunLog.setRunId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"));
        when(mockIssueStore.startRun()).thenReturn(mockRunLog);

        // Default scout stubs
        when(mockScout.discoverTargetRepos(anyInt())).thenReturn(List.of(REPO));
        when(mockScout.fetchGoodFirstIssues(any(), anyInt())).thenReturn(List.of(ISSUE));

        // Default: issue not yet in DB
        when(mockIssueStore.findExisting(anyString(), anyInt())).thenReturn(Optional.empty());

        // Default: createPending returns a record with id=1
        when(mockIssueStore.createPending(any())).thenReturn(issueRecord(1L));

        // Default: SweService obtained from context
        when(mockContext.getBean(SweService.class)).thenReturn(mockSwe);

        // Default: reporter returns a minimal fix report
        when(mockReporter.generateReport(any(), any()))
                .thenReturn(new com.gitsolve.model.FixReport(
                        "title", "url", "body", "analysis", "", "", "", "FAILED", 0,
                        List.of(), java.time.Instant.now()));
    }

    // ------------------------------------------------------------------ //
    // TC-01: Success path — fix accepted by reviewer                      //
    // ------------------------------------------------------------------ //

    @Test
    void runFixLoop_successPath_markSuccessCalled() throws Exception {
        when(mockTriage.triageBatch(any()))
                .thenReturn(List.of(triageResult(ISSUE)));
        when(mockSwe.fix(any()))
                .thenReturn(fixResult(true, "+Objects.requireNonNull(value);", null, 2));
        when(mockReviewer.review(any(), any()))
                .thenReturn(new ReviewResult(true, List.of(), "Fix looks good."));

        orchestrator.runFixLoop();

        verify(mockIssueStore).markSuccess(eq(1L), anyString(), anyString(), eq(2));
        verify(mockMetrics).recordIssueSucceeded();
        verify(mockMetrics).recordIterationCount(2);
        verify(mockIssueStore, never()).markFailed(anyLong(), anyString(), anyInt());
    }

    // ------------------------------------------------------------------ //
    // TC-02: SWE failure — fix loop exhausted                             //
    // ------------------------------------------------------------------ //

    @Test
    void runFixLoop_sweFailure_markFailedWithReason() throws Exception {
        when(mockTriage.triageBatch(any()))
                .thenReturn(List.of(triageResult(ISSUE)));
        when(mockSwe.fix(any()))
                .thenReturn(fixResult(false, "", "Exhausted 5 iterations", 5));

        orchestrator.runFixLoop();

        verify(mockIssueStore).markFailed(eq(1L), contains("Exhausted"), eq(5));
        verify(mockMetrics).recordIssueFailed("swe_failed");
        verify(mockIssueStore, never()).markSuccess(anyLong(), anyString(), anyString(), anyInt());
    }

    // ------------------------------------------------------------------ //
    // TC-03: Reviewer rejection — fix valid but violates constraints      //
    // ------------------------------------------------------------------ //

    @Test
    void runFixLoop_reviewerRejects_markFailedWithReviewerRejectedReason() throws Exception {
        when(mockTriage.triageBatch(any()))
                .thenReturn(List.of(triageResult(ISSUE)));
        when(mockSwe.fix(any()))
                .thenReturn(fixResult(true, "+System.out.println(\"debug\");", null, 1));
        when(mockReviewer.review(any(), any()))
                .thenReturn(new ReviewResult(false,
                        List.of("System.out.println is forbidden"), "Violation found."));

        orchestrator.runFixLoop();

        verify(mockIssueStore).markFailed(eq(1L), contains("Reviewer rejected"), eq(1));
        verify(mockMetrics).recordIssueFailed("reviewer_rejected");
        verify(mockIssueStore, never()).markSuccess(anyLong(), anyString(), anyString(), anyInt());
    }

    // ------------------------------------------------------------------ //
    // TC-04: Already processed — createPending never called               //
    // ------------------------------------------------------------------ //

    @Test
    void runFixLoop_alreadyProcessed_createPendingNeverCalled() throws Exception {
        when(mockTriage.triageBatch(any()))
                .thenReturn(List.of(triageResult(ISSUE)));
        when(mockIssueStore.findExisting(anyString(), eq(42)))
                .thenReturn(Optional.of(issueRecord(99L)));

        orchestrator.runFixLoop();

        verify(mockIssueStore, never()).createPending(any());
        verify(mockSwe, never()).fix(any());
    }

    // ------------------------------------------------------------------ //
    // TC-05: Token budget exhausted before processing                     //
    // ------------------------------------------------------------------ //

    @Test
    void runFixLoop_tokenBudgetZero_noIssuesProcessed() throws Exception {
        when(mockProps.llm().maxTokensPerRun()).thenReturn(0);
        when(mockTriage.triageBatch(any()))
                .thenReturn(List.of(triageResult(ISSUE)));

        orchestrator.runFixLoop();

        verify(mockIssueStore, never()).createPending(any());
        verify(mockSwe, never()).fix(any());
    }

    // ------------------------------------------------------------------ //
    // Helpers                                                              //
    // ------------------------------------------------------------------ //

    private static TriageResult triageResult(GitIssue issue) {
        return new TriageResult(issue, IssueComplexity.EASY, "Clear bug.", true);
    }

    private static FixResult fixResult(boolean success, String diff,
                                        String failureReason, int iterations) {
        List<FixAttempt> attempts = java.util.Collections.nCopies(
                iterations,
                new FixAttempt(1, "Foo.java", diff, "", success && iterations == 1));
        return new FixResult(
                "apache/commons-lang#42", success, attempts, diff, failureReason);
    }

    private static IssueRecord issueRecord(Long id) {
        IssueRecord r = new IssueRecord();
        r.setId(id);
        return r;
    }
}
