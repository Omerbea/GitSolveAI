-- V7__add_fix_instructions.sql
-- Stores the AI-generated fix instructions produced by the Fix Instructions Agent.
-- Populated on demand via the dashboard UI — not generated automatically on every run.

ALTER TABLE issue_records ADD COLUMN IF NOT EXISTS fix_instructions TEXT;
