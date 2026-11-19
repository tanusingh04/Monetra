-- V3__immutability_and_audit_triggers.sql: PostgreSQL Engine-Level Financial Immutability Triggers

-- 1. Create Trigger Function to block any UPDATE or DELETE on ledger_entries
CREATE OR REPLACE FUNCTION prevent_ledger_tampering()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Double-entry ledger records are strictly immutable. UPDATE and DELETE operations are prohibited.';
END;
$$ LANGUAGE plpgsql;

-- 2. Attach Trigger to ledger_entries table
DROP TRIGGER IF EXISTS trg_immutable_ledger_entries ON ledger_entries;
CREATE TRIGGER trg_immutable_ledger_entries
BEFORE UPDATE OR DELETE ON ledger_entries
FOR EACH ROW
EXECUTE FUNCTION prevent_ledger_tampering();

-- 3. Composite Index on idempotency_keys for fast in-progress lease timeout lookups
CREATE INDEX IF NOT EXISTS idx_idempotency_status_updated ON idempotency_keys(status, updated_at);
