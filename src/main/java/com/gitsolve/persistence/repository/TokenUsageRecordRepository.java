package com.gitsolve.persistence.repository;

import com.gitsolve.persistence.entity.TokenUsageRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TokenUsageRecordRepository extends JpaRepository<TokenUsageRecord, Long> {

    List<TokenUsageRecord> findByRunId(UUID runId);
}
