package com.gitsolve.persistence;

import com.gitsolve.model.AppSettings;
import com.gitsolve.persistence.entity.AppSettingsEntity;
import com.gitsolve.persistence.repository.AppSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Facade for reading and writing the single app_settings row (id=1).
 * Always returns a non-null AppSettings — falls back to defaults if the row is missing.
 */
@Service
@Transactional
public class SettingsStore {

    private static final Logger log = LoggerFactory.getLogger(SettingsStore.class);
    private static final long SETTINGS_ROW_ID = 1L;

    private final AppSettingsRepository repository;

    public SettingsStore(AppSettingsRepository repository) {
        this.repository = repository;
    }

    /**
     * Loads current settings from the DB. Never throws — returns defaults on any error.
     */
    @Transactional(readOnly = true)
    public AppSettings load() {
        try {
            return repository.findById(SETTINGS_ROW_ID)
                    .map(AppSettingsEntity::getSettings)
                    .orElseGet(() -> {
                        log.warn("SettingsStore: no settings row found — using defaults");
                        return AppSettings.defaults();
                    });
        } catch (Exception e) {
            log.error("SettingsStore: failed to load settings — using defaults: {}", e.getMessage());
            return AppSettings.defaults();
        }
    }

    /**
     * Persists new settings to the DB (upsert on id=1).
     */
    public void save(AppSettings settings) {
        AppSettingsEntity entity = repository.findById(SETTINGS_ROW_ID)
                .orElseGet(() -> {
                    AppSettingsEntity e = new AppSettingsEntity();
                    e.setId(SETTINGS_ROW_ID);
                    return e;
                });
        entity.setSettings(settings);
        repository.save(entity);
        log.info("SettingsStore: saved settings scoutMode={} maxRepos={}",
                settings.scoutMode(), settings.maxReposPerRun());
    }
}
