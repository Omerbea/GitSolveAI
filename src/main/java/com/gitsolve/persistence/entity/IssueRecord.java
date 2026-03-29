package com.gitsolve.persistence.entity;

import com.gitsolve.model.ConstraintJson;
import com.gitsolve.model.IssueComplexity;
import com.gitsolve.model.IssueStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * JPA entity mirroring the issue_records table.
 * Agents must NOT access this entity directly — use IssueStore.
 */
@Entity
@Table(name = "issue_records")
public class IssueRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "issue_id", nullable = false)
    private String issueId;

    @Column(name = "repo_url", nullable = false)
    private String repoUrl;

    @Column(name = "repo_full_name", nullable = false)
    private String repoFullName;

    @Column(name = "issue_number", nullable = false)
    private Integer issueNumber;

    @Column(name = "issue_title", nullable = false)
    private String issueTitle;

    @Column(name = "issue_body", columnDefinition = "TEXT")
    private String issueBody;

    @Enumerated(EnumType.STRING)
    @Column(name = "complexity")
    private IssueComplexity complexity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private IssueStatus status;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "fix_diff", columnDefinition = "TEXT")
    private String fixDiff;

    @Column(name = "fix_summary", columnDefinition = "TEXT")
    private String fixSummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "constraint_json", columnDefinition = "jsonb")
    private ConstraintJson constraintJson;

    @Column(name = "iteration_count")
    private Integer iterationCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    // ------------------------------------------------------------------ //
    // Getters and setters                                                  //
    // ------------------------------------------------------------------ //

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getIssueId() { return issueId; }
    public void setIssueId(String issueId) { this.issueId = issueId; }

    public String getRepoUrl() { return repoUrl; }
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }

    public String getRepoFullName() { return repoFullName; }
    public void setRepoFullName(String repoFullName) { this.repoFullName = repoFullName; }

    public Integer getIssueNumber() { return issueNumber; }
    public void setIssueNumber(Integer issueNumber) { this.issueNumber = issueNumber; }

    public String getIssueTitle() { return issueTitle; }
    public void setIssueTitle(String issueTitle) { this.issueTitle = issueTitle; }

    public String getIssueBody() { return issueBody; }
    public void setIssueBody(String issueBody) { this.issueBody = issueBody; }

    public IssueComplexity getComplexity() { return complexity; }
    public void setComplexity(IssueComplexity complexity) { this.complexity = complexity; }

    public IssueStatus getStatus() { return status; }
    public void setStatus(IssueStatus status) { this.status = status; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public String getFixDiff() { return fixDiff; }
    public void setFixDiff(String fixDiff) { this.fixDiff = fixDiff; }

    public String getFixSummary() { return fixSummary; }
    public void setFixSummary(String fixSummary) { this.fixSummary = fixSummary; }

    public ConstraintJson getConstraintJson() { return constraintJson; }
    public void setConstraintJson(ConstraintJson constraintJson) { this.constraintJson = constraintJson; }

    public Integer getIterationCount() { return iterationCount; }
    public void setIterationCount(Integer iterationCount) { this.iterationCount = iterationCount; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
