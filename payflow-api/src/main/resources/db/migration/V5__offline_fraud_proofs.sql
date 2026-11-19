-- V5__offline_fraud_proofs.sql: Offline Double-Spend Fraud Proofs & Immutability Trigger

-- 1. Ensure offline_transactions stores raw signed receipt_payload for independent verification
ALTER TABLE offline_transactions
ADD COLUMN IF NOT EXISTS receipt_payload TEXT;

-- 2. Create offline_fraud_proofs table
CREATE TABLE IF NOT EXISTS offline_fraud_proofs (
    id UUID PRIMARY KEY,
    allowance_id UUID NOT NULL REFERENCES offline_allowances(id),
    device_id VARCHAR(128) NOT NULL,
    wallet_id UUID NOT NULL REFERENCES wallets(id),
    counter BIGINT NOT NULL,
    first_tx_id UUID NOT NULL,
    second_tx_id UUID NOT NULL,
    first_receipt_payload TEXT NOT NULL,
    first_signature TEXT NOT NULL,
    second_receipt_payload TEXT NOT NULL,
    second_signature TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    detected_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    reviewed_by VARCHAR(100),
    reviewed_at TIMESTAMP WITH TIME ZONE,
    review_note TEXT,
    CONSTRAINT uq_offline_fraud_proofs_equivocation UNIQUE (allowance_id, counter, first_tx_id, second_tx_id),
    CONSTRAINT chk_fraud_proof_status CHECK (status IN ('OPEN', 'UNDER_REVIEW', 'CONFIRMED', 'DISMISSED'))
);

CREATE INDEX IF NOT EXISTS idx_offline_fraud_proofs_status ON offline_fraud_proofs(status);
CREATE INDEX IF NOT EXISTS idx_offline_fraud_proofs_allowance ON offline_fraud_proofs(allowance_id);
CREATE INDEX IF NOT EXISTS idx_offline_fraud_proofs_device ON offline_fraud_proofs(device_id);
CREATE INDEX IF NOT EXISTS idx_offline_fraud_proofs_wallet ON offline_fraud_proofs(wallet_id);

-- 3. Immutability Trigger for Fraud Proofs (Payloads & signatures cannot be modified or deleted)
CREATE OR REPLACE FUNCTION prevent_fraud_proof_tampering()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Offline fraud proofs are strictly immutable and cannot be deleted.';
    END IF;

    IF TG_OP = 'UPDATE' THEN
        -- Only status, reviewed_by, reviewed_at, and review_note may be updated
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

DROP TRIGGER IF EXISTS trg_immutable_fraud_proofs ON offline_fraud_proofs;
CREATE TRIGGER trg_immutable_fraud_proofs
BEFORE UPDATE OR DELETE ON offline_fraud_proofs
FOR EACH ROW
EXECUTE FUNCTION prevent_fraud_proof_tampering();
