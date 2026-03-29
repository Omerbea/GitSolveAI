package com.gitsolve.persistence;

import com.gitsolve.model.*;
import com.gitsolve.persistence.entity.IssueRecord;
import com.gitsolve.persistence.entity.RunLog;
import com.gitsolve.persistence.entity.TokenUsageRecord;
import com.gitsolve.persistence.repository.IssueRecordRepository;
import com.gitsolve.persistence.repository.RunLogRepository;
import com.gitsolve.persistence.repository.TokenUsageRecordRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The ONLY persistence facade that agents and the orchestrator may call.
 * All state transitions on IssueRecord must go through this class.
 * Direct access to repositories from outside the persistence package is prohibited.
 */
@Service
@Transactional
public class IssueStore {

    private final IssueRecordRepository      repository;
    private final RunLogRepository           runLogRepository;
    private final TokenUsageRecordRepository tokenUsageRepository;

    public IssueStore(IssueRecordRepository repository,
                      RunLogRepository runLogRepository,
                      TokenUsageRecordRepository tokenUsageRepository) {
        this.repository           = repository;
        this.runLogRepository     = runLogRepository;
        this.tokenUsageRepository = tokenUsageRepository;
    }

    /**
     * Creates a new IssueRecord in PENDING status.
     * Throws IllegalStateException if a record already exists for (repoUrl, issueNumber).
     */
    public IssueRecord createPending(GitIssue issue) {
        IssueRecord record = new IssueRecord();
        record.setIssueId(issue.repoFullName() + "#" + issue.issueNumber());
        record.setRepoUrl(deriveRepoUrl(issue.repoFullName()));
        record.setRepoFullName(issue.repoFullName());
        record.setIssueNumber(issue.issueNumber());
        record.setIssueTitle(issue.title());
        record.setIssueBody(issue.body());
        record.setStatus(IssueStatus.PENDING);
        record.setIterationCount(0);
        return repository.save(record);
    }

    /**
     * Transitions PENDING → IN_PROGRESS.
     */
    public void markInProgress(Long id) {
        IssueRecord record = findOrThrow(id);
        record.setStatus(IssueStatus.IN_PROGRESS);
        repository.save(record);
    }

    /**
     * Transitions IN_PROGRESS → SUCCESS.
     */
    public void markSuccess(Long id, String finalDiff, String summary, int iterations) {
        IssueRecord record = findOrThrow(id);
        record.setStatus(IssueStatus.SUCCESS);
        record.setFixDiff(finalDiff);
        record.setFixSummary(summary);
        record.setIterationCount(iterations);
        record.setCompletedAt(Instant.now());
        repository.save(record);
    }

    /**
     * Transitions IN_PROGRESS → FAILED.
     */
    public void markFailed(Long id, String reason, int iterations) {
        IssueRecord record = findOrThrow(id);
        record.setStatus(IssueStatus.FAILED);
        record.setFailureReason(reason);
        record.setIterationCount(iterations);
        record.setCompletedAt(Instant.now());
        repository.save(record);
    }

    /**
     * Transitions PENDING → SKIPPED (issue rejected before processing).
     */
    public void markSkipped(Long id, String reason) {
        IssueRecord record = findOrThrow(id);
        record.setStatus(IssueStatus.SKIPPED);
        record.setFailureReason(reason);
        record.setCompletedAt(Instant.now());
        repository.save(record);
    }

    /**
     * Persists extracted CONTRIBUTING.md constraints into the record.
     */
    public void saveConstraintJson(Long id, ConstraintJson constraints) {
        IssueRecord record = findOrThrow(id);
        record.setConstraintJson(constraints);
        repository.save(record);
    }

    /**
     * Looks up an existing record by (repoUrl, issueNumber).
     * Used by the orchestrator to skip already-processed issues.
     */
    @Transactional(readOnly = true)
    public Optional<IssueRecord> findExisting(String repoUrl, int issueNumber) {
        return repository.findByRepoUrlAndIssueNumber(repoUrl, issueNumber);
    }

    /**
     * Persists a {@link com.gitsolve.model.FixReport} onto the issue record.
     * Called after every SWE Agent attempt — success or failure.
     */
    public void saveFixReport(Long id, com.gitsolve.model.FixReport report) {
        IssueRecord record = findOrThrow(id);
        record.setFixReport(report);
        repository.save(record);
    }

    /**
     * Looks up a single IssueRecord by its primary key.
     * Used by the dashboard diff view.
     */
    @Transactional(readOnly = true)
    public Optional<IssueRecord> findById(Long id) {
        return repository.findById(id);
    }

    /**
     * Returns all records with the given status.
     */
    @Transactional(readOnly = true)
    public List<IssueRecord> findByStatus(IssueStatus status) {
        return repository.findByStatus(status);
    }

    /**
     * Aggregate statistics across all records (for dashboard and metrics).
     */
    @Transactional(readOnly = true)
    public RunStats currentRunStats() {
        return new RunStats(
                (int) repository.countByStatus(IssueStatus.PENDING),
                (int) repository.countByStatus(IssueStatus.IN_PROGRESS),
                (int) repository.countByStatus(IssueStatus.SUCCESS),
                (int) repository.countByStatus(IssueStatus.FAILED),
                (int) repository.countByStatus(IssueStatus.SKIPPED)
        );
    }

    // ------------------------------------------------------------------ //
    // Run lifecycle                                                        //
    // ------------------------------------------------------------------ //

    /**
     * Creates a new RunLog in RUNNING status.
     * Call at the start of each scheduled run; hold the returned entity to pass
     * its id and runId to {@link #finishRun} and {@link #recordTokenUsage}.
     */
    public RunLog startRun() {
        RunLog run = new RunLog();
        run.setRunId(UUID.randomUUID());
        run.setStartedAt(Instant.now());
        run.setStatus(RunStatus.RUNNING);
        return runLogRepository.save(run);
    }

    /**
     * Closes a RunLog (RUNNING → COMPLETED or FAILED) with final statistics.
     */
    public void finishRun(Long runLogId, int scouted, int triaged,
                          int attempted, int succeeded, int tokenUsage,
                          RunStatus status) {
        RunLog run = runLogRepository.findById(runLogId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "RunLog not found for id=" + runLogId));
        run.setFinishedAt(Instant.now());
        run.setIssuesScouted(scouted);
        run.setIssuesTriaged(triaged);
        run.setIssuesAttempted(attempted);
        run.setIssuesSucceeded(succeeded);
        run.setTokenUsage(tokenUsage);
        run.setStatus(status);
        runLogRepository.save(run);
    }

    /**
     * Persists a per-agent token usage record linked to the current run.
     *
     * @param runId        UUID from the RunLog (links token rows to a run)
     * @param agentName    e.g. "swe", "triage", "reviewer"
     * @param model        LLM model name
     * @param inputTokens  input token count (0 if unavailable)
     * @param outputTokens output token count (0 if unavailable)
     */
    public void recordTokenUsage(UUID runId, String agentName, String model,
                                 int inputTokens, int outputTokens) {
        TokenUsageRecord record = new TokenUsageRecord();
        record.setRunId(runId);
        record.setAgentName(agentName);
        record.setModel(model);
        record.setInputTokens(inputTokens);
        record.setOutputTokens(outputTokens);
        record.setRecordedAt(Instant.now());
        tokenUsageRepository.save(record);
    }

    /**
     * Returns the most recent runs ordered by startedAt descending.
     *
     * @param limit maximum number of rows to return
     */
    @Transactional(readOnly = true)
    public List<RunLog> recentRuns(int limit) {
        return runLogRepository.findAll(
                PageRequest.of(0, limit, Sort.by("startedAt").descending())).getContent();
    }

    // ------------------------------------------------------------------ //
    // Private helpers                                                      //
    // ------------------------------------------------------------------ //

    private IssueRecord findOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "IssueRecord not found for id=" + id));
    }

    /**
     * Derives a canonical repo URL from a fullName like "apache/commons-lang".
     * Uses HTTPS URL format to match GitHub clone URLs.
     */
    private static String deriveRepoUrl(String repoFullName) {
        return "https://github.com/" + repoFullName;
    }
}
