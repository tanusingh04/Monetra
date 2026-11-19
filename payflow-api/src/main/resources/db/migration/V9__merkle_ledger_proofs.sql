-- V9__merkle_ledger_proofs.sql: Cryptographically Sealed Merkle-Tree Ledger Batches and Inclusion Proofs

-- 1. Merkle Batches (Append-only batch root commitments)
CREATE TABLE IF NOT EXISTS ledger_merkle_batches (
    batch_number BIGINT PRIMARY KEY,
    leaf_count INT NOT NULL,
    root_hash CHAR(64) NOT NULL,
    previous_root_hash CHAR(64) NOT NULL,
    chain_hash CHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    first_entry_created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_entry_created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- 2. Merkle Leaves (Append-only mapping of ledger entries to batch leaves)
CREATE TABLE IF NOT EXISTS ledger_merkle_leaves (
    entry_id UUID PRIMARY KEY REFERENCES ledger_entries(id),
    batch_number BIGINT NOT NULL REFERENCES ledger_merkle_batches(batch_number),
    leaf_index INT NOT NULL,
    leaf_hash CHAR(64) NOT NULL,
    CONSTRAINT uq_merkle_batch_leaf_index UNIQUE(batch_number, leaf_index)
);

CREATE INDEX IF NOT EXISTS idx_merkle_leaves_batch ON ledger_merkle_leaves(batch_number);

-- 3. PostgreSQL Immutability Triggers (Prohibits UPDATE and DELETE)
CREATE OR REPLACE FUNCTION prevent_merkle_tampering()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Merkle ledger batches and leaves are cryptographically sealed and strictly immutable. UPDATE and DELETE operations are prohibited.';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_immutable_merkle_batches ON ledger_merkle_batches;
CREATE TRIGGER trg_immutable_merkle_batches
BEFORE UPDATE OR DELETE ON ledger_merkle_batches
FOR EACH ROW
EXECUTE FUNCTION prevent_merkle_tampering();

DROP TRIGGER IF EXISTS trg_immutable_merkle_leaves ON ledger_merkle_leaves;
CREATE TRIGGER trg_immutable_merkle_leaves
BEFORE UPDATE OR DELETE ON ledger_merkle_leaves
FOR EACH ROW
EXECUTE FUNCTION prevent_merkle_tampering();
