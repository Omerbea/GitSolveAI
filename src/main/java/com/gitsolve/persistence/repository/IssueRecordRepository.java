package com.gitsolve.persistence.repository;

import com.gitsolve.model.IssueStatus;
import com.gitsolve.persistence.entity.IssueRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IssueRecordRepository extends JpaRepository<IssueRecord, Long> {

    Optional<IssueRecord> findByRepoUrlAndIssueNumber(String repoUrl, int issueNumber);

    List<IssueRecord> findByStatus(IssueStatus status);

    List<IssueRecord> findByStatusOrderByCreatedAtDesc(IssueStatus status, Pageable pageable);

    long countByStatus(IssueStatus status);
}
