-- V2__create_run_logs.sql
-- Tracks each daily run of the fix loop

CREATE TABLE run_logs (
    id               BIGSERIAL PRIMARY KEY,
    run_id           UUID        NOT NULL DEFAULT gen_random_uuid(),
    started_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at      TIMESTAMPTZ,
    issues_scouted   INTEGER     NOT NULL DEFAULT 0,
    issues_triaged   INTEGER     NOT NULL DEFAULT 0,
    issues_attempted INTEGER     NOT NULL DEFAULT 0,
    issues_succeeded INTEGER     NOT NULL DEFAULT 0,
    token_usage      INTEGER     NOT NULL DEFAULT 0,
    status           TEXT        NOT NULL DEFAULT 'RUNNING'
                                 CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_run_logs_run_id     ON run_logs (run_id);
CREATE INDEX idx_run_logs_started_at ON run_logs (started_at DESC);
