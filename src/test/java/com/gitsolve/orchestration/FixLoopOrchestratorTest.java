package com.gitsolve.orchestration;

import com.gitsolve.agent.analysis.AnalysisResult;
import com.gitsolve.agent.analysis.AnalysisService;
import com.gitsolve.agent.scout.ScoutService;
import com.gitsolve.agent.triage.TriageService;
import com.gitsolve.config.GitSolveProperties;
import com.gitsolve.dashboard.SseEmitterRegistry;
import com.gitsolve.model.*;
import com.gitsolve.persistence.IssueStore;
import com.gitsolve.persistence.SettingsStore;
import com.gitsolve.persistence.entity.IssueRecord;
import com.gitsolve.persistence.entity.RunLog;
import com.gitsolve.telemetry.AgentMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FixLoopOrchestrator — no Spring context, pure Mockito mocks.
 */
@Tag("unit")
class FixLoopOrchestratorTest {

    private ScoutService       mockScout;
    private TriageService      mockTriage;
    private AnalysisService    mockAnalysis;
    private IssueStore         mockIssueStore;
    private AgentMetrics       mockMetrics;
    private GitSolveProperties mockProps;

    private FixLoopOrchestrator orchestrator;

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
        mockScout      = mock(ScoutService.class);
        mockTriage     = mock(TriageService.class);
        mockAnalysis   = mock(AnalysisService.class);
        mockIssueStore = mock(IssueStore.class);
        mockMetrics    = mock(AgentMetrics.class);

        mockProps = mock(GitSolveProperties.class);
        GitSolveProperties.GitHub github = mock(GitSolveProperties.GitHub.class);
        GitSolveProperties.Llm    llm    = mock(GitSolveProperties.Llm.class);
        when(mockProps.github()).thenReturn(github);
        when(mockProps.llm()).thenReturn(llm);
        when(github.maxReposPerRun()).thenReturn(1);
        when(github.maxIssuesPerRepo()).thenReturn(5);
        when(llm.maxTokensPerRun()).thenReturn(500_000);
        when(llm.model()).thenReturn("claude-haiku-4-5-20251001");

        SettingsStore mockSettings = mock(SettingsStore.class);
        when(mockSettings.load()).thenReturn(AppSettings.defaults());

        SseEmitterRegistry mockSse = mock(SseEmitterRegistry.class);

        orchestrator = new FixLoopOrchestrator(
                mockScout, mockTriage, mockAnalysis,
                mockIssueStore, mockSettings, mockMetrics, mockProps, mockSse);

        RunLog mockRunLog = new RunLog();
        mockRunLog.setId(10L);
        mockRunLog.setRunId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        when(mockIssueStore.startRun()).thenReturn(mockRunLog);

        when(mockScout.discoverTargetRepos(anyInt())).thenReturn(List.of(REPO));
        when(mockScout.fetchGoodFirstIssues(any(), anyInt())).thenReturn(List.of(ISSUE));
        when(mockIssueStore.findExisting(anyString(), anyInt())).thenReturn(Optional.empty());
        when(mockIssueStore.createPending(any())).thenReturn(issueRecord(1L));

        when(mockAnalysis.analyse(any(), any())).thenReturn(new AnalysisResult(
                "Root cause: missing null check.",
                List.of("src/main/java/StringUtils.java"),
                "Add a null guard before the isEmpty call.",
                "EASY",
                "Null-check pattern already used in adjacent methods."
        ));
    }

    // ------------------------------------------------------------------ //
    // TC-01: Happy path — issue gets analysed and marked SUCCESS          //
    // ------------------------------------------------------------------ //

    @Test
    void runFixLoop_happyPath_markSuccessCalled() {
        when(mockTriage.triageBatch(any()))
                .thenReturn(List.of(triageResult(ISSUE)));

        orchestrator.runFixLoop();

        verify(mockIssueStore).markSuccess(eq(1L), anyString(), anyString(), eq(0));
        verify(mockMetrics).recordIssueSucceeded();
        verify(mockIssueStore, never()).markFailed(anyLong(), anyString(), anyInt());
    }

    // ------------------------------------------------------------------ //
    // TC-02: Analysis throws — issue marked FAILED                        //
    // ------------------------------------------------------------------ //

    @Test
    void runFixLoop_analysisThrows_markFailedCalled() {
        when(mockTriage.triageBatch(any()))
                .thenReturn(List.of(triageResult(ISSUE)));
        when(mockAnalysis.analyse(any(), any()))
                .thenThrow(new RuntimeException("LLM timeout"));

        orchestrator.runFixLoop();

        verify(mockIssueStore).markFailed(eq(1L), contains("Analysis error"), eq(0));
        verify(mockMetrics).recordIssueFailed("analysis_error");
        verify(mockIssueStore, never()).markSuccess(anyLong(), anyString(), anyString(), anyInt());
    }

    // ------------------------------------------------------------------ //
    // TC-03: Already processed — createPending never called               //
    // ------------------------------------------------------------------ //

    @Test
    void runFixLoop_alreadyProcessed_createPendingNeverCalled() {
        when(mockTriage.triageBatch(any()))
                .thenReturn(List.of(triageResult(ISSUE)));
        when(mockIssueStore.findExisting(anyString(), eq(42)))
                .thenReturn(Optional.of(issueRecord(99L)));

        orchestrator.runFixLoop();

        verify(mockIssueStore, never()).createPending(any());
        verify(mockAnalysis, never()).analyse(any(), any());
    }

    // ------------------------------------------------------------------ //
    // TC-04: Token budget zero — no issues processed                      //
    // ------------------------------------------------------------------ //

    @Test
    void runFixLoop_tokenBudgetZero_noIssuesProcessed() {
        when(mockProps.llm().maxTokensPerRun()).thenReturn(0);
        when(mockTriage.triageBatch(any()))
                .thenReturn(List.of(triageResult(ISSUE)));

        orchestrator.runFixLoop();

        verify(mockIssueStore, never()).createPending(any());
        verify(mockAnalysis, never()).analyse(any(), any());
    }

    // ------------------------------------------------------------------ //
    // TC-05: Fix report saved after successful analysis                   //
    // ------------------------------------------------------------------ //

    @Test
    void runFixLoop_happyPath_fixReportSaved() {
        when(mockTriage.triageBatch(any()))
                .thenReturn(List.of(triageResult(ISSUE)));

        orchestrator.runFixLoop();

        verify(mockIssueStore).saveFixReport(eq(1L), argThat(r ->
                r.buildStatus().equals("ANALYSED") &&
                r.rootCauseAnalysis().contains("null check")));
    }

    // ------------------------------------------------------------------ //
    // Helpers                                                              //
    // ------------------------------------------------------------------ //

    private static TriageResult triageResult(GitIssue issue) {
        return new TriageResult(issue, IssueComplexity.EASY, "Clear bug.", true);
    }

    private static IssueRecord issueRecord(Long id) {
        IssueRecord r = new IssueRecord();
        r.setId(id);
        return r;
    }
}
