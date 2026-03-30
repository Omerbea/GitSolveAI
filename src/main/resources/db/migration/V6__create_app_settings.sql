-- V6__create_app_settings.sql
-- Single-row settings table. The app always reads/writes row id=1.
-- settings JSONB stores the full AppSettings record so the schema evolves freely.

CREATE TABLE IF NOT EXISTS app_settings (
    id       BIGINT PRIMARY KEY DEFAULT 1,
    settings JSONB  NOT NULL DEFAULT '{}'
);

-- Seed the default row so the app always finds exactly one row.
INSERT INTO app_settings (id, settings)
VALUES (1, '{
    "scoutMode": "PINNED",
    "targetRepos": ["apache/commons-lang", "javaparser/javaparser", "diffplug/spotless"],
    "starMin": 100,
    "starMax": 3000,
    "maxReposPerRun": 2,
    "maxIssuesPerRepo": 5
}')
ON CONFLICT (id) DO NOTHING;
