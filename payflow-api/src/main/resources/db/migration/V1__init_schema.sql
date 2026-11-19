-- V1__init_schema.sql: PayFlow Core Database Schema

-- Enable UUID extension if supported
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- 1. USERS TABLE
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(30) NOT NULL DEFAULT 'ROLE_USER',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 2. WALLETS TABLE
CREATE TABLE IF NOT EXISTS wallets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE RESTRICT,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    balance NUMERIC(19, 4) NOT NULL DEFAULT 0.0000,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_wallet_balance_non_negative CHECK (balance >= 0),
    CONSTRAINT chk_wallet_currency CHECK (currency IN ('INR', 'USD', 'EUR'))
);

-- 3. PAYMENTS TABLE
CREATE TABLE IF NOT EXISTS payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    sender_wallet_id UUID NOT NULL REFERENCES wallets(id) ON DELETE RESTRICT,
    receiver_wallet_id UUID NOT NULL REFERENCES wallets(id) ON DELETE RESTRICT,
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    failure_reason TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_payment_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_payment_sender_receiver_different CHECK (sender_wallet_id != receiver_wallet_id)
);

-- 4. LEDGER ENTRIES TABLE (Double-Entry Financial Book of Record)
CREATE TABLE IF NOT EXISTS ledger_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id UUID NOT NULL REFERENCES payments(id) ON DELETE RESTRICT,
    wallet_id UUID NOT NULL REFERENCES wallets(id) ON DELETE RESTRICT,
    entry_type VARCHAR(10) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    balance_after NUMERIC(19, 4) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_ledger_entry_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_ledger_entry_type CHECK (entry_type IN ('DEBIT', 'CREDIT'))
);

-- 5. IDEMPOTENCY KEYS TABLE
CREATE TABLE IF NOT EXISTS idempotency_keys (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    response_code INTEGER,
    response_body TEXT,
    payment_id UUID REFERENCES payments(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 6. TRANSACTIONAL OUTBOX EVENTS TABLE
CREATE TABLE IF NOT EXISTS outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP WITH TIME ZONE
);

-- 7. PROCESSED EVENTS TABLE (Application-level deduplication for Kafka consumers)
CREATE TABLE IF NOT EXISTS processed_events (
    event_id UUID NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (event_id, consumer_group)
);

-- 8. RECONCILIATION REPORTS TABLE
CREATE TABLE IF NOT EXISTS reconciliation_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    status VARCHAR(30) NOT NULL,
    total_wallets_checked INTEGER NOT NULL DEFAULT 0,
    mismatched_wallets_count INTEGER NOT NULL DEFAULT 0,
    total_ledger_debits NUMERIC(19, 4) NOT NULL DEFAULT 0.0000,
    total_ledger_credits NUMERIC(19, 4) NOT NULL DEFAULT 0.0000,
    discrepancy_details TEXT,
    executed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
