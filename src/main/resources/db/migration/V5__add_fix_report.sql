-- V5__add_fix_report.sql
-- Stores the SWE Agent's structured investigation report per issue.
-- Generated regardless of build outcome — captures investigation value even for failed fixes.

ALTER TABLE issue_records ADD COLUMN IF NOT EXISTS fix_report JSONB;
