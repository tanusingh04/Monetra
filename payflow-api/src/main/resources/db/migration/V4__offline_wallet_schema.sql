-- V4: PayFlow Offline Wallet Schema
-- Adds reserved balance tracking to wallets and introduces device enrollments,
-- offline allowances, and offline transaction records.

-- 1. Extend Wallets with Reserved Balance for Offline Allowances
ALTER TABLE wallets
ADD COLUMN IF NOT EXISTS reserved_balance NUMERIC(19, 4) NOT NULL DEFAULT 0.0000;

ALTER TABLE wallets
ADD CONSTRAINT chk_wallets_reserved_balance_non_negative CHECK (reserved_balance >= 0);

ALTER TABLE wallets
ADD CONSTRAINT chk_wallets_balance_gte_reserved CHECK (balance >= reserved_balance);

-- 2. Device Enrollments Table (Stores Device Public Keys for Signature Verification)
CREATE TABLE IF NOT EXISTS device_enrollments (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    device_id VARCHAR(128) NOT NULL,
    device_model VARCHAR(128),
    public_key TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    enrolled_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    revoked_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_device_enrollments_user_device UNIQUE (user_id, device_id)
);

CREATE INDEX IF NOT EXISTS idx_device_enrollments_device_id ON device_enrollments(device_id);
CREATE INDEX IF NOT EXISTS idx_device_enrollments_status ON device_enrollments(status);

-- 3. Offline Allowances Table
CREATE TABLE IF NOT EXISTS offline_allowances (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    wallet_id UUID NOT NULL REFERENCES wallets(id),
    device_id VARCHAR(128) NOT NULL,
    allocated_amount NUMERIC(19, 4) NOT NULL,
    spent_amount NUMERIC(19, 4) NOT NULL DEFAULT 0.0000,
    max_single_tx_amount NUMERIC(19, 4) NOT NULL,
    last_counter BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    server_signature TEXT NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    settled_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_allowance_allocated_positive CHECK (allocated_amount > 0),
    CONSTRAINT chk_allowance_spent_non_negative CHECK (spent_amount >= 0),
    CONSTRAINT chk_allowance_spent_lte_allocated CHECK (spent_amount <= allocated_amount)
);

CREATE INDEX IF NOT EXISTS idx_offline_allowances_wallet ON offline_allowances(wallet_id);
CREATE INDEX IF NOT EXISTS idx_offline_allowances_status ON offline_allowances(status);
CREATE INDEX IF NOT EXISTS idx_offline_allowances_expires_at ON offline_allowances(expires_at);

-- 4. Offline Transactions Table (Records Peer-to-Peer Signed Receipts upon Sync)
CREATE TABLE IF NOT EXISTS offline_transactions (
    id UUID PRIMARY KEY,
    allowance_id UUID NOT NULL REFERENCES offline_allowances(id),
    sender_wallet_id UUID NOT NULL REFERENCES wallets(id),
    receiver_wallet_id UUID NOT NULL REFERENCES wallets(id),
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    counter BIGINT NOT NULL,
    nonce VARCHAR(64) NOT NULL,
    device_signature TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    rejection_reason TEXT,
    payment_id UUID REFERENCES payments(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    synchronized_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_offline_tx_allowance_counter UNIQUE (allowance_id, counter),
    CONSTRAINT chk_offline_tx_amount_positive CHECK (amount > 0)
);

CREATE INDEX IF NOT EXISTS idx_offline_transactions_allowance ON offline_transactions(allowance_id);
CREATE INDEX IF NOT EXISTS idx_offline_transactions_sender ON offline_transactions(sender_wallet_id);
CREATE INDEX IF NOT EXISTS idx_offline_transactions_receiver ON offline_transactions(receiver_wallet_id);
CREATE INDEX IF NOT EXISTS idx_offline_transactions_status ON offline_transactions(status);
