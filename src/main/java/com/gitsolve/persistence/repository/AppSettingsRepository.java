package com.gitsolve.persistence.repository;

import com.gitsolve.persistence.entity.AppSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingsRepository extends JpaRepository<AppSettingsEntity, Long> {
}
