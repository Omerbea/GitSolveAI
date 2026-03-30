package com.gitsolve.orchestration;

import com.gitsolve.agent.analysis.AnalysisResult;
import com.gitsolve.agent.analysis.AnalysisService;
import com.gitsolve.agent.execution.ExecutionService;
import com.gitsolve.agent.execution.NoopProgressReporter;
import com.gitsolve.agent.instructions.FixInstructionsService;
import com.gitsolve.agent.reviewer.ReviewerService;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Fix Loop Orchestrator — discovers issues, triages them, analyses each one,
 * generates fix instructions, executes the fix in a sandboxed environment,
 * and runs a governance review before submitting a pull request.
 *
 * <p>Pipeline per run:
 * <ol>
 *   <li><b>Scout</b>: discover repos, fetch good-first-issues.</li>
 *   <li><b>Triage</b>: batch-classify; only EASY/MEDIUM issues proceed.</li>
 *   <li><b>Analysis Agent</b>: produce a structured investigation report
 *       (root cause, affected files, suggested approach).</li>
 *   <li><b>Fix Instructions</b>: generate repo-specific coding instructions.</li>
 *   <li><b>Execution Agent</b>: fork → clone → LLM fix → build loop → push → PR.</li>
 *   <li><b>Reviewer Agent</b>: governance check on the produced diff.</li>
 * </ol>
 */
@Component
public class FixLoopOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(FixLoopOrchestrator.class);

    private final ScoutService           scoutService;
    private final TriageService          triageService;
    private final AnalysisService        analysisService;
    private final IssueStore             issueStore;
    private final SettingsStore          settingsStore;
    private final AgentMetrics           metrics;
    private final GitSolveProperties     props;
    private final SseEmitterRegistry     sseRegistry;
    private final ApplicationContext     applicationContext;
    private final FixInstructionsService fixInstructionsService;
    private final ReviewerService        reviewerService;

    @Autowired
    public FixLoopOrchestrator(
            ScoutService           scoutService,
            TriageService          triageService,
            AnalysisService        analysisService,
            IssueStore             issueStore,
            SettingsStore          settingsStore,
            AgentMetrics           metrics,
            GitSolveProperties     props,
            SseEmitterRegistry     sseRegistry,
            ApplicationContext     applicationContext,
            FixInstructionsService fixInstructionsService,
            ReviewerService        reviewerService) {
        this.scoutService           = scoutService;
        this.triageService          = triageService;
        this.analysisService        = analysisService;
        this.issueStore             = issueStore;
        this.settingsStore          = settingsStore;
        this.metrics                = metrics;
        this.props                  = props;
        this.sseRegistry            = sseRegistry;
        this.applicationContext     = applicationContext;
        this.fixInstructionsService = fixInstructionsService;
        this.reviewerService        = reviewerService;
    }

    // ------------------------------------------------------------------ //
    // Scheduled entry point                                                //
    // ------------------------------------------------------------------ //

    @Scheduled(cron = "${gitsolve.schedule.cron}")
    public void runFixLoop() {
        AppSettings settings       = settingsStore.load();
        int         maxRepos       = settings.maxReposPerRun() > 0
                                        ? settings.maxReposPerRun()
                                        : props.github().maxReposPerRun();
        int         maxIssuesPerRepo = settings.maxIssuesPerRepo() > 0
                                        ? settings.maxIssuesPerRepo()
                                        : props.github().maxIssuesPerRepo();

        log.info("[FixLoop] Starting run — maxRepos={}, maxIssuesPerRepo={}, tokenBudget={}",
                maxRepos, maxIssuesPerRepo, props.llm().maxTokensPerRun());

        RunLog runLog = issueStore.startRun();
        log.info("[FixLoop] Run started runId={}", runLog.getRunId());

        int tokenCount = 0;
        int analysed   = 0;
        int failed     = 0;
        List<GitRepository> repos    = List.of();
        List<TriageResult>  accepted = List.of();

        try {
            // --- Scout ---
            repos = scoutService.discoverTargetRepos(maxRepos);
            log.info("[FixLoop] Discovered {} repos", repos.size());

            List<GitIssue> allIssues = scoutService.fetchGoodFirstIssues(repos, maxIssuesPerRepo);
            log.info("[FixLoop] Fetched {} issues across all repos", allIssues.size());

            // --- Triage ---
            accepted = triageService.triageBatch(allIssues);
            log.info("[FixLoop] Triage accepted {}/{} issues", accepted.size(), allIssues.size());

            // --- Analysis loop ---
            for (TriageResult triageResult : accepted) {
                GitIssue issue   = triageResult.issue();
                String   issueId = issue.repoFullName() + "#" + issue.issueNumber();
                String   repoUrl = "https://github.com/" + issue.repoFullName();

                // Token budget check (~2k tokens per analysis call)
                if (tokenCount >= props.llm().maxTokensPerRun()) {
                    log.warn("[FixLoop] Daily token budget exhausted — stopping after {} issues",
                            analysed + failed);
                    break;
                }

                // Idempotency: skip already-analysed issues
                if (issueStore.findExisting(repoUrl, issue.issueNumber()).isPresent()) {
                    log.warn("[FixLoop] {} already in database — skipping", issueId);
                    metrics.recordIssueAttempted("orchestrator");
                    continue;
                }

                IssueRecord record   = issueStore.createPending(issue);
                Long        recordId = record.getId();
                issueStore.markInProgress(recordId);
                metrics.recordIssueAttempted("analysis");

                try {
                    // --- Analysis Agent ---
                    AnalysisResult analysis = analysisService.analyse(issue, triageResult);
                    tokenCount += 2_000; // rough estimate per call

                    issueStore.recordTokenUsage(runLog.getRunId(), "analysis",
                            props.llm().model(), 2_000, 500);

                    // Persist as FixReport so the existing dashboard/DB works unchanged
                    String affectedFilesStr = analysis.affectedFiles() != null
                            ? String.join(", ", analysis.affectedFiles())
                            : "";

                    FixReport report = new FixReport(
                            issue.title(),
                            "https://github.com/" + issue.repoFullName() + "/issues/" + issue.issueNumber(),
                            issue.body() != null
                                    ? issue.body().substring(0, Math.min(500, issue.body().length()))
                                    : "",
                            analysis.rootCauseAnalysis(),
                            affectedFilesStr,
                            analysis.suggestedApproach(),   // reuse proposedCode for approach
                            "",                             // no diff
                            "ANALYSED",
                            0,
                            buildIterationHistory(analysis),
                            Instant.now()
                    );

                    issueStore.saveFixReport(recordId, report);

                    // --- Fix Instructions ---
                    String fixInstructions = fixInstructionsService.generate(
                            issue.repoFullName(), issue.issueNumber(), issue.title(), report);
                    issueStore.saveFixInstructions(recordId, fixInstructions);
                    issueStore.markExecuting(recordId);

                    // --- Execution Agent (prototype — fresh memory per issue) ---
                    ExecutionService executionService =
                            applicationContext.getBean(ExecutionService.class);
                    executionService.setProgressReporter(recordId, NoopProgressReporter.INSTANCE);
                    ExecutionResult executionResult = executionService.execute(issue, fixInstructions, analysis.affectedFiles());

                    if (executionResult.success()) {
                        // --- Reviewer Agent ---
                        FixResult fixResult = new FixResult(
                                issue.repoFullName() + "#" + issue.issueNumber(),
                                true,
                                List.of(),
                                executionResult.diff(),
                                null);
                        ReviewResult reviewResult = reviewerService.review(fixResult, issue);

                        FixReport updatedReport = new FixReport(
                                report.issueTitle(),
                                report.issueUrl(),
                                report.issueBodyExcerpt(),
                                report.rootCauseAnalysis(),
                                report.proposedFilePath(),
                                report.proposedCode(),
                                executionResult.diff(),
                                "PASSED",
                                executionResult.iterations(),
                                report.iterationHistory(),
                                Instant.now());

                        issueStore.saveFixReport(recordId, updatedReport);
                        issueStore.markPrSubmitted(recordId, executionResult.prUrl());
                        metrics.recordIssueSucceeded();

                        log.info("[FixLoop] {} → PR_SUBMITTED prUrl={} reviewApproved={}",
                                issueId, executionResult.prUrl(), reviewResult.approved());

                        // Push to all connected dashboard clients
                        sseRegistry.broadcast("issue-analysed", buildIssueEvent(
                                recordId, issue, analysis, affectedFilesStr));

                    } else {
                        issueStore.markExecutionFailed(recordId, executionResult.failureReason());
                        metrics.recordIssueFailed("execution_failed");
                        log.info("[FixLoop] {} → EXECUTION_FAILED reason={}",
                                issueId, executionResult.failureReason());
                    }

                    log.info("[FixLoop] {} → ANALYSED (complexity={})", issueId,
                            analysis.estimatedComplexity());
                    analysed++;

                } catch (Exception e) {
                    log.error("[FixLoop] {} — Analysis error: {}", issueId, e.getMessage(), e);
                    issueStore.markFailed(recordId, "Analysis error: " + e.getMessage(), 0);
                    metrics.recordIssueFailed("analysis_error");
                    failed++;
                }
            }

            issueStore.finishRun(runLog.getId(), repos.size(), accepted.size(),
                    analysed + failed, analysed, tokenCount, RunStatus.COMPLETED);
            log.info("[FixLoop] Run finished runId={} status=COMPLETED analysed={} failed={} tokenEstimate={}",
                    runLog.getRunId(), analysed, failed, tokenCount);

            sseRegistry.broadcast("run-complete",
                    "{\"status\":\"COMPLETED\",\"analysed\":" + analysed +
                    ",\"failed\":" + failed + "}");

        } catch (Exception e) {
            log.error("[FixLoop] Run failed with unexpected error: {}", e.getMessage(), e);
            issueStore.finishRun(runLog.getId(), repos.size(), accepted.size(),
                    analysed + failed, analysed, tokenCount, RunStatus.FAILED);
            log.info("[FixLoop] Run finished runId={} status=FAILED", runLog.getRunId());
        }
    }

    private List<String> buildIterationHistory(AnalysisResult analysis) {
        if (analysis.relevantPatterns() != null && !analysis.relevantPatterns().isBlank()) {
            return List.of(
                    "Complexity: " + analysis.estimatedComplexity(),
                    "Patterns: " + analysis.relevantPatterns()
            );
        }
        return List.of("Complexity: " + analysis.estimatedComplexity());
    }

    private String buildIssueEvent(Long recordId, GitIssue issue,
                                   AnalysisResult analysis, String affectedFiles) {
        String title    = jsonEscape(issue.title());
        String repo     = jsonEscape(issue.repoFullName());
        String complexity = jsonEscape(
                analysis.estimatedComplexity() != null ? analysis.estimatedComplexity() : "UNKNOWN");
        String files    = jsonEscape(affectedFiles);
        return "{\"id\":" + recordId +
               ",\"issueNumber\":" + issue.issueNumber() +
               ",\"issueTitle\":\"" + title + "\"" +
               ",\"repoFullName\":\"" + repo + "\"" +
               ",\"complexity\":\"" + complexity + "\"" +
               ",\"affectedFiles\":\"" + files + "\"" +
               ",\"statusIcon\":\"analysed\"}";
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }
}
