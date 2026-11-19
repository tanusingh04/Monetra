-- V6__receiver_protected_settlement.sql: Receiver-Protected Settlement & Grace Settlements

-- 1. Add revocation_reason to device_enrollments and offline_allowances, and rejection_category to offline_transactions
ALTER TABLE device_enrollments
ADD COLUMN IF NOT EXISTS revocation_reason VARCHAR(30);

ALTER TABLE offline_allowances
ADD COLUMN IF NOT EXISTS revocation_reason VARCHAR(30);

ALTER TABLE offline_transactions
ADD COLUMN IF NOT EXISTS rejection_category VARCHAR(30);

-- 2. Add outcome columns to offline_fraud_proofs
ALTER TABLE offline_fraud_proofs
ADD COLUMN IF NOT EXISTS second_receipt_settled BOOLEAN NOT NULL DEFAULT FALSE,
ADD COLUMN IF NOT EXISTS protected_amount NUMERIC(19, 4) NOT NULL DEFAULT 0.0000,
ADD COLUMN IF NOT EXISTS uncovered_amount NUMERIC(19, 4) NOT NULL DEFAULT 0.0000,
ADD COLUMN IF NOT EXISTS settlement_payment_id UUID REFERENCES payments(id);

-- 3. Update Immutability Trigger for offline_fraud_proofs
-- Evidence columns remain strictly immutable. Outcome columns and review columns can be updated or set.
CREATE OR REPLACE FUNCTION prevent_fraud_proof_tampering()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Offline fraud proofs are strictly immutable and cannot be deleted.';
    END IF;

    IF TG_OP = 'UPDATE' THEN
        -- Cryptographic evidence columns MUST NOT change
        IF (OLD.id IS DISTINCT FROM NEW.id OR
            OLD.allowance_id IS DISTINCT FROM NEW.allowance_id OR
            OLD.device_id IS DISTINCT FROM NEW.device_id OR
            OLD.wallet_id IS DISTINCT FROM NEW.wallet_id OR
            OLD.counter IS DISTINCT FROM NEW.counter OR
            OLD.first_tx_id IS DISTINCT FROM NEW.first_tx_id OR
            OLD.second_tx_id IS DISTINCT FROM NEW.second_tx_id OR
            OLD.first_receipt_payload IS DISTINCT FROM NEW.first_receipt_payload OR
            OLD.first_signature IS DISTINCT FROM NEW.first_signature OR
            OLD.second_receipt_payload IS DISTINCT FROM NEW.second_receipt_payload OR
            OLD.second_signature IS DISTINCT FROM NEW.second_signature OR
            OLD.detected_at IS DISTINCT FROM NEW.detected_at) THEN
            RAISE EXCEPTION 'Cryptographic evidence (payloads, signatures, counter, IDs) in fraud proofs is strictly immutable.';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 4. Create offline_protected_settlements table
-- Preserves the strict UNIQUE(allowance_id, counter) constraint on offline_transactions
-- while persisting double-spend receipts that are honored/protected by reserved funds.
CREATE TABLE IF NOT EXISTS offline_protected_settlements (
    tx_id UUID PRIMARY KEY,
    proof_id UUID NOT NULL REFERENCES offline_fraud_proofs(id),
    payment_id UUID NOT NULL REFERENCES payments(id),
    amount NUMERIC(19, 4) NOT NULL,
    receipt_payload TEXT NOT NULL,
    device_signature TEXT NOT NULL,
    settled_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_protected_settlement_amount_positive CHECK (amount > 0)
);

CREATE INDEX IF NOT EXISTS idx_offline_protected_proof ON offline_protected_settlements(proof_id);
CREATE INDEX IF NOT EXISTS idx_offline_protected_payment ON offline_protected_settlements(payment_id);

-- 5. Create offline_rejected_receipts table
-- Persists rejected receipts when counter already exists on offline_transactions
CREATE TABLE IF NOT EXISTS offline_rejected_receipts (
    tx_id UUID PRIMARY KEY,
    allowance_id UUID REFERENCES offline_allowances(id),
    sender_wallet_id UUID REFERENCES wallets(id),
    receiver_wallet_id UUID REFERENCES wallets(id),
    amount NUMERIC(19, 4),
    currency VARCHAR(3) DEFAULT 'INR',
    counter BIGINT,
    rejection_reason TEXT NOT NULL,
    category VARCHAR(30) DEFAULT 'SENDER_ATTRIBUTABLE',
    rejected_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_offline_rejected_allowance ON offline_rejected_receipts(allowance_id);

