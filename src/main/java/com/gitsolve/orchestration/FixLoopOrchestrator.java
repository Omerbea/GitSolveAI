package com.gitsolve.orchestration;

import com.gitsolve.agent.reporter.ReporterService;
import com.gitsolve.agent.reviewer.ReviewerService;
import com.gitsolve.agent.scout.ScoutService;
import com.gitsolve.agent.swe.SweService;
import com.gitsolve.agent.triage.TriageService;
import com.gitsolve.config.GitSolveProperties;
import com.gitsolve.docker.BuildEnvironmentException;
import com.gitsolve.model.*;
import com.gitsolve.persistence.IssueStore;
import com.gitsolve.persistence.entity.IssueRecord;
import com.gitsolve.persistence.entity.RunLog;
import com.gitsolve.telemetry.AgentMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fix Loop Orchestrator — the daily pipeline that wires all four agents.
 *
 * <p>Pipeline per run:
 * <ol>
 *   <li><b>Scout</b>: discover top N active Java repos, fetch good-first-issues.</li>
 *   <li><b>Triage</b>: batch-classify issues; only EASY/MEDIUM accepted issues proceed.</li>
 *   <li><b>Per accepted issue</b> (up to daily token budget):
 *     <ul>
 *       <li>Skip if already persisted in IssueStore.</li>
 *       <li>PENDING → IN_PROGRESS.</li>
 *       <li><b>SWE Agent</b>: run iterative fix loop in Docker sandbox.</li>
 *       <li>If fix succeeded → <b>Reviewer</b>: validate diff against constraints.</li>
 *       <li>Transition to SUCCESS or FAILED; record metrics.</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>{@link SweService} is obtained fresh via {@link ApplicationContext#getBean} per issue
 * (prototype scope) to ensure each fix gets its own {@code ChatMemory} and no state leaks
 * between issues.
 *
 * <p>The cron expression is read from {@code gitsolve.schedule.cron} (default: midnight daily).
 * {@link #runFixLoop()} is package-accessible so tests can invoke it directly without
 * relying on Spring scheduling infrastructure.
 */
@Component
public class FixLoopOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(FixLoopOrchestrator.class);

    /**
     * Rough token-cost estimate per fix iteration.
     * Used to enforce the daily token budget without requiring per-agent token instrumentation
     * at this stage. Each iteration sends ~1k tokens (context + prompt + response).
     */
    private static final int TOKENS_PER_ITERATION_ESTIMATE = 1_000;

    private final ScoutService       scoutService;
    private final TriageService      triageService;
    private final ReviewerService    reviewerService;
    private final ReporterService    reporterService;
    private final IssueStore         issueStore;
    private final AgentMetrics       metrics;
    private final GitSolveProperties props;
    private final ApplicationContext context;

    public FixLoopOrchestrator(
            ScoutService       scoutService,
            TriageService      triageService,
            ReviewerService    reviewerService,
            ReporterService    reporterService,
            IssueStore         issueStore,
            AgentMetrics       metrics,
            GitSolveProperties props,
            ApplicationContext context) {
        this.scoutService    = scoutService;
        this.triageService   = triageService;
        this.reviewerService = reviewerService;
        this.reporterService = reporterService;
        this.issueStore      = issueStore;
        this.metrics         = metrics;
        this.props           = props;
        this.context         = context;
    }

    // ------------------------------------------------------------------ //
    // Scheduled entry point                                                //
    // ------------------------------------------------------------------ //

    @Scheduled(cron = "${gitsolve.schedule.cron}")
    public void runFixLoop() {
        log.info("[FixLoop] Starting run — maxRepos={}, maxIssuesPerRepo={}, tokenBudget={}",
                props.github().maxReposPerRun(),
                props.github().maxIssuesPerRepo(),
                props.llm().maxTokensPerRun());

        // --- Start run log ---
        RunLog runLog = issueStore.startRun();
        log.info("[FixLoop] Run started runId={}", runLog.getRunId());

        int tokenCount = 0;
        int succeeded  = 0;
        int failed     = 0;
        List<GitRepository> repos = List.of();
        List<TriageResult>  accepted = List.of();

        try {
            // --- Scout ---
            repos = scoutService.discoverTargetRepos(props.github().maxReposPerRun());
            log.info("[FixLoop] Discovered {} repos", repos.size());

            List<GitIssue> allIssues = scoutService.fetchGoodFirstIssues(
                    repos, props.github().maxIssuesPerRepo());
            log.info("[FixLoop] Fetched {} issues across all repos", allIssues.size());

            // --- Triage ---
            accepted = triageService.triageBatch(allIssues);
            log.info("[FixLoop] Triage accepted {}/{} issues", accepted.size(), allIssues.size());

            // --- Fix loop ---
            for (TriageResult triageResult : accepted) {
                GitIssue issue   = triageResult.issue();
                String   issueId = issue.repoFullName() + "#" + issue.issueNumber();
                String   repoUrl = "https://github.com/" + issue.repoFullName();

                // Token budget check
                if (tokenCount >= props.llm().maxTokensPerRun()) {
                    log.warn("[FixLoop] Daily token budget exhausted (used={}) — stopping after {} issues",
                            tokenCount, succeeded + failed);
                    break;
                }

                // Idempotency: skip already-processed issues
                if (issueStore.findExisting(repoUrl, issue.issueNumber()).isPresent()) {
                    log.warn("[FixLoop] {} already in database — skipping", issueId);
                    metrics.recordIssueAttempted("orchestrator");
                    continue;
                }

                // Create persistence record and transition to IN_PROGRESS
                IssueRecord record = issueStore.createPending(issue);
                Long recordId = record.getId();
                issueStore.markInProgress(recordId);
                metrics.recordIssueAttempted("swe");

                try {
                    // --- SWE Agent (fresh prototype per issue) ---
                    SweService swe = context.getBean(SweService.class);
                    FixResult fix  = swe.fix(issue);

                    int iterationCount = fix.attempts() != null ? fix.attempts().size() : 0;
                    int iterationTokens = iterationCount * TOKENS_PER_ITERATION_ESTIMATE;
                    tokenCount += iterationTokens;

                    // Record token usage for this issue's SWE run
                    issueStore.recordTokenUsage(runLog.getRunId(), "swe",
                            props.llm().model(), 0, iterationTokens);

                    if (fix.success()) {
                        // --- Reviewer ---
                        ReviewResult review = reviewerService.review(fix, issue);

                        if (review.approved()) {
                            issueStore.markSuccess(recordId, fix.finalDiff(),
                                    review.summary(), iterationCount);
                            metrics.recordIssueSucceeded();
                            metrics.recordIterationCount(iterationCount);
                            log.info("[FixLoop] {} → SUCCESS (iterations={})", issueId, iterationCount);
                            succeeded++;
                        } else {
                            String reason = "Reviewer rejected: " + review.violations();
                            issueStore.markFailed(recordId, reason, iterationCount);
                            metrics.recordIssueFailed("reviewer_rejected");
                            log.info("[FixLoop] {} → FAILED (reviewer rejected: {})",
                                    issueId, review.violations());
                            failed++;
                        }

                    } else {
                        issueStore.markFailed(recordId, fix.failureReason(), iterationCount);
                        metrics.recordIssueFailed("swe_failed");
                        log.info("[FixLoop] {} → FAILED ({})", issueId, fix.failureReason());
                        failed++;
                    }

                    // --- Fix Report (always generated, regardless of outcome) ---
                    try {
                        FixReport report = reporterService.generateReport(fix, issue);
                        issueStore.saveFixReport(recordId, report);
                        log.debug("[FixLoop] {} fix report saved", issueId);
                    } catch (Exception re) {
                        log.warn("[FixLoop] {} failed to save fix report: {}", issueId, re.getMessage());
                    }

                } catch (BuildEnvironmentException e) {
                    log.error("[FixLoop] {} — Docker error: {}", issueId, e.getMessage(), e);
                    issueStore.markFailed(recordId, "Docker error: " + e.getMessage(), 0);
                    metrics.recordIssueFailed("docker_error");
                    failed++;
                } catch (Exception e) {
                    log.error("[FixLoop] {} — Unexpected error: {}", issueId, e.getMessage(), e);
                    issueStore.markFailed(recordId, "Unexpected error: " + e.getMessage(), 0);
                    metrics.recordIssueFailed("error");
                    failed++;
                }
            }

            issueStore.finishRun(runLog.getId(), repos.size(), accepted.size(),
                    succeeded + failed, succeeded, tokenCount, RunStatus.COMPLETED);
            log.info("[FixLoop] Run finished runId={} status=COMPLETED succeeded={} failed={} tokenEstimate={}",
                    runLog.getRunId(), succeeded, failed, tokenCount);

        } catch (Exception e) {
            log.error("[FixLoop] Run failed with unexpected error: {}", e.getMessage(), e);
            issueStore.finishRun(runLog.getId(), repos.size(), accepted.size(),
                    succeeded + failed, succeeded, tokenCount, RunStatus.FAILED);
            log.info("[FixLoop] Run finished runId={} status=FAILED", runLog.getRunId());
        }
    }
}
