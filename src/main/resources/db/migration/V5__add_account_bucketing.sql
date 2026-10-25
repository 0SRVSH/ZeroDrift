ALTER TABLE accounts
    ADD COLUMN bucket_count INTEGER NOT NULL DEFAULT 1
        CHECK (bucket_count > 0);

ALTER TABLE account_snapshots
    DROP CONSTRAINT uq_account_snapshots_account_id,
    ADD COLUMN bucket_index INTEGER NOT NULL DEFAULT 0
        CHECK (bucket_index >= 0),
    ADD COLUMN last_posting_sequence BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT uq_account_snapshots_account_bucket
        UNIQUE (account_id, bucket_index);

CREATE SEQUENCE posting_sequence_seq;

ALTER TABLE postings
    ADD COLUMN bucket_index INTEGER NOT NULL DEFAULT 0
        CHECK (bucket_index >= 0),
    ADD COLUMN posting_sequence BIGINT NOT NULL DEFAULT nextval('posting_sequence_seq'::regclass);

ALTER SEQUENCE posting_sequence_seq OWNED BY postings.posting_sequence;

CREATE UNIQUE INDEX uq_postings_posting_sequence
    ON postings (posting_sequence);

UPDATE account_snapshots snapshot
SET last_posting_sequence = COALESCE((
    SELECT MAX(posting.posting_sequence)
    FROM postings posting
    WHERE posting.account_id = snapshot.account_id
      AND posting.bucket_index = snapshot.bucket_index
), 0);
