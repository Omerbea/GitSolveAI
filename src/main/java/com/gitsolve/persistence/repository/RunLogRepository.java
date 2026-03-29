package com.gitsolve.persistence.repository;

import com.gitsolve.model.RunStatus;
import com.gitsolve.persistence.entity.RunLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RunLogRepository extends JpaRepository<RunLog, Long> {

    Optional<RunLog> findFirstByOrderByStartedAtDesc();

    List<RunLog> findByStatus(RunStatus status);
}
