-- V1__create_issues.sql
-- Core table for tracking issue fix attempts

CREATE TABLE issue_records (
    id                  BIGSERIAL PRIMARY KEY,
    issue_id            TEXT        NOT NULL,
    repo_url            TEXT        NOT NULL,
    repo_full_name      TEXT        NOT NULL,
    issue_number        INTEGER     NOT NULL,
    issue_title         TEXT        NOT NULL,
    issue_body          TEXT,
    complexity          TEXT        CHECK (complexity IN ('EASY', 'MEDIUM'))  DEFAULT NULL,
    status              TEXT        NOT NULL DEFAULT 'PENDING'
                                    CHECK (status IN ('PENDING', 'IN_PROGRESS', 'SUCCESS', 'FAILED', 'SKIPPED')),
    failure_reason      TEXT,
    fix_diff            TEXT,
    fix_summary         TEXT,
    constraint_json     JSONB,
    iteration_count     INTEGER     NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at        TIMESTAMPTZ,
    CONSTRAINT uq_issue UNIQUE (repo_url, issue_number)
);

CREATE INDEX idx_issue_records_status     ON issue_records (status);
CREATE INDEX idx_issue_records_created_at ON issue_records (created_at DESC);
CREATE INDEX idx_issue_records_repo       ON issue_records (repo_full_name);
