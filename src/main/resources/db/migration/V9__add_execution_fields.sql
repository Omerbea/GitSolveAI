-- V9__add_execution_fields.sql
-- Adds columns to track the execution agent state and the submitted PR URL.

ALTER TABLE issue_records ADD COLUMN IF NOT EXISTS pr_url TEXT;
ALTER TABLE issue_records ADD COLUMN IF NOT EXISTS execution_status TEXT
    CHECK (execution_status IN ('EXECUTING', 'PR_SUBMITTED', 'EXECUTION_FAILED'));
