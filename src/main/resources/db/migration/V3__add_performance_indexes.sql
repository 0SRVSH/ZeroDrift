-- Accelerates historical balance lookups and account auditing
CREATE INDEX idx_postings_account_created_at
ON postings (account_id, created_at DESC);

-- Fast lookup for all legs of a given journal entry
CREATE INDEX idx_postings_journal_id
ON postings (journal_entry_id);

-- Speeds up lookup for in-flight requests and background expiry routines
CREATE INDEX idx_idempotency_expires_at
ON idempotency_records (expires_at)
WHERE status = 'PENDING';

-- Accelerates chronological auditing
CREATE INDEX idx_journal_entries_posted_at
ON journal_entries (posted_at DESC);
