package com.gitsolve.persistence;

import com.gitsolve.model.*;
import com.gitsolve.persistence.entity.IssueRecord;
import com.gitsolve.persistence.repository.IssueRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The ONLY persistence facade that agents and the orchestrator may call.
 * All state transitions on IssueRecord must go through this class.
 * Direct access to IssueRecordRepository from outside the persistence package is prohibited.
 */
@Service
@Transactional
public class IssueStore {

    private final IssueRecordRepository repository;

    public IssueStore(IssueRecordRepository repository) {
        this.repository = repository;
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
