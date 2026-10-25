-- 1. Idempotency State Machine Table
-- Guarantees atomic request deduplication across retries
CREATE TABLE idempotency_records (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'COMMITTED', 'REJECTED')),
    request_hash VARCHAR(64) NOT NULL,
    response_payload JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL
);

-- 2. Master Accounts Table
CREATE TABLE accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_number VARCHAR(64) UNIQUE NOT NULL,
    currency VARCHAR(3) NOT NULL,
    account_type VARCHAR(20) NOT NULL CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 3. Materialized Balance Snapshots Table
-- Serves as the target for row-level locks (SELECT ... FOR UPDATE) to avoid scanning historical postings
CREATE TABLE account_snapshots (
    account_id UUID PRIMARY KEY REFERENCES accounts(id) ON DELETE RESTRICT,
    cleared_balance NUMERIC(18, 4) NOT NULL DEFAULT 0.0000,
    version BIGINT NOT NULL DEFAULT 0,
    last_journal_entry_id UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 4. Journal Entries Table
-- Encapsulates a distinct business transaction linking balanced posting legs
CREATE TABLE journal_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(128) UNIQUE NOT NULL REFERENCES idempotency_records(idempotency_key) ON DELETE RESTRICT,
    narration TEXT NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('POSTED', 'VOIDED')),
    posted_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 5. Postings Table
-- Immutable double-entry legs; modifications and deletions are prohibited
CREATE TABLE postings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    journal_entry_id UUID NOT NULL REFERENCES journal_entries(id) ON DELETE RESTRICT,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    direction VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount NUMERIC(18, 4) NOT NULL CHECK (amount > 0),
    sequence_order SMALLINT NOT NULL CHECK (sequence_order >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_journal_entry_sequence UNIQUE (journal_entry_id, sequence_order)
);
