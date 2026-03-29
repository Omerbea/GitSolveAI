package com.gitsolve.persistence.entity;

import com.gitsolve.model.RunStatus;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mirroring the run_logs table.
 * One row per daily run execution.
 */
@Entity
@Table(name = "run_logs")
public class RunLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "issues_scouted")
    private Integer issuesScouted = 0;

    @Column(name = "issues_triaged")
    private Integer issuesTriaged = 0;

    @Column(name = "issues_attempted")
    private Integer issuesAttempted = 0;

    @Column(name = "issues_succeeded")
    private Integer issuesSucceeded = 0;

    @Column(name = "token_usage")
    private Integer tokenUsage = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RunStatus status;

    // ------------------------------------------------------------------ //
    // Getters and setters                                                  //
    // ------------------------------------------------------------------ //

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }

    public Integer getIssuesScouted() { return issuesScouted; }
    public void setIssuesScouted(Integer issuesScouted) { this.issuesScouted = issuesScouted; }

    public Integer getIssuesTriaged() { return issuesTriaged; }
    public void setIssuesTriaged(Integer issuesTriaged) { this.issuesTriaged = issuesTriaged; }

    public Integer getIssuesAttempted() { return issuesAttempted; }
    public void setIssuesAttempted(Integer issuesAttempted) { this.issuesAttempted = issuesAttempted; }

    public Integer getIssuesSucceeded() { return issuesSucceeded; }
    public void setIssuesSucceeded(Integer issuesSucceeded) { this.issuesSucceeded = issuesSucceeded; }

    public Integer getTokenUsage() { return tokenUsage; }
    public void setTokenUsage(Integer tokenUsage) { this.tokenUsage = tokenUsage; }

    public RunStatus getStatus() { return status; }
    public void setStatus(RunStatus status) { this.status = status; }
}
