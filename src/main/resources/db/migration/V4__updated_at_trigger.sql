-- V4__updated_at_trigger.sql
-- Automatically maintains updated_at timestamp on issue_records

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_issue_records_updated_at
BEFORE UPDATE ON issue_records
FOR EACH ROW EXECUTE FUNCTION set_updated_at();
