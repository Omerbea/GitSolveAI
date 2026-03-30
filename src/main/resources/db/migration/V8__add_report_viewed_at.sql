-- V8__add_report_viewed_at.sql
-- Tracks when a user first viewed the investigation report for an issue.
-- NULL = never viewed. Set on first GET /issues/{id}/report.

ALTER TABLE issue_records ADD COLUMN IF NOT EXISTS report_viewed_at TIMESTAMP WITH TIME ZONE;
