-- V7__user_reputation.sql: Reputation-Based Allowance Limits & Append-Only Event Log

-- 1. Create user_reputation table
CREATE TABLE IF NOT EXISTS user_reputation (
    user_id UUID PRIMARY KEY REFERENCES users(id),
    score INT NOT NULL DEFAULT 100,
    tier VARCHAR(20) NOT NULL DEFAULT 'NEW',
    clean_settled_count INT NOT NULL DEFAULT 0,
    settled_amount_total NUMERIC(19, 4) NOT NULL DEFAULT 0.0000,
    rejected_count INT NOT NULL DEFAULT 0,
    fraud_proofs_detected INT NOT NULL DEFAULT 0,
    fraud_proofs_confirmed INT NOT NULL DEFAULT 0,
    restricted BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_reputation_score_non_negative CHECK (score >= 0)
);

CREATE INDEX IF NOT EXISTS idx_user_reputation_tier ON user_reputation(tier);
CREATE INDEX IF NOT EXISTS idx_user_reputation_restricted ON user_reputation(restricted);

-- 2. Create append-only reputation_events table
CREATE TABLE IF NOT EXISTS reputation_events (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    event_type VARCHAR(50) NOT NULL,
    score_delta INT NOT NULL,
    score_after INT NOT NULL,
    reason TEXT NOT NULL,
    ref_id VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_reputation_events_user ON reputation_events(user_id);
CREATE INDEX IF NOT EXISTS idx_reputation_events_created ON reputation_events(created_at);

-- 3. Immutability Trigger for reputation_events (Append-only: UPDATE and DELETE are strictly prohibited)
CREATE OR REPLACE FUNCTION prevent_reputation_events_tampering()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Reputation events are strictly immutable and append-only. UPDATE and DELETE operations are prohibited.';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_immutable_reputation_events ON reputation_events;
CREATE TRIGGER trg_immutable_reputation_events
BEFORE UPDATE OR DELETE ON reputation_events
FOR EACH ROW
EXECUTE FUNCTION prevent_reputation_events_tampering();
