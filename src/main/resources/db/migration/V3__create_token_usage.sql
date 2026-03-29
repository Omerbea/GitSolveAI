-- V3__create_token_usage.sql
-- Per-agent token usage tracking for budget enforcement and cost visibility

CREATE TABLE token_usage (
    id            BIGSERIAL PRIMARY KEY,
    run_id        UUID        NOT NULL,
    agent_name    TEXT        NOT NULL,
    model         TEXT        NOT NULL,
    input_tokens  INTEGER     NOT NULL DEFAULT 0,
    output_tokens INTEGER     NOT NULL DEFAULT 0,
    recorded_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_token_usage_run  ON token_usage (run_id);
CREATE INDEX idx_token_usage_date ON token_usage (recorded_at DESC);
