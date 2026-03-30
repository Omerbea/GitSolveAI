package com.gitsolve.persistence.entity;

import com.gitsolve.model.AppSettings;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity backing the app_settings table.
 * Always one row with id=1. Use SettingsStore to read/write — never access directly.
 */
@Entity
@Table(name = "app_settings")
public class AppSettingsEntity {

    @Id
    private Long id = 1L;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings", nullable = false, columnDefinition = "jsonb")
    private AppSettings settings;

    public Long getId()                       { return id; }
    public void setId(Long id)                { this.id = id; }
    public AppSettings getSettings()          { return settings; }
    public void setSettings(AppSettings s)    { this.settings = s; }
}
