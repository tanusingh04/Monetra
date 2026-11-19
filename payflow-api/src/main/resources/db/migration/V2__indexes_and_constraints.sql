-- V2__indexes_and_constraints.sql: Performance Indexes & Audit Constraints

-- Index payments by sender/receiver wallet for fast ledger lookups and statement generation
CREATE INDEX IF NOT EXISTS idx_payments_sender_wallet ON payments(sender_wallet_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_payments_receiver_wallet ON payments(receiver_wallet_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_payments_status ON payments(status);

-- Index ledger entries by wallet and timestamp for balance audits and transaction history
CREATE INDEX IF NOT EXISTS idx_ledger_wallet_created ON ledger_entries(wallet_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ledger_payment_id ON ledger_entries(payment_id);
CREATE INDEX IF NOT EXISTS idx_ledger_entry_type ON ledger_entries(entry_type);

-- Index outbox events for high-throughput polling (SKIP LOCKED)
CREATE INDEX IF NOT EXISTS idx_outbox_status_created ON outbox_events(status, created_at ASC);

-- Index idempotency records
CREATE INDEX IF NOT EXISTS idx_idempotency_created ON idempotency_keys(created_at);

-- Index users by email & username
CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
