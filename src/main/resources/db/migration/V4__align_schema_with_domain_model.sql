ALTER TABLE account_snapshots
    ADD COLUMN id UUID DEFAULT gen_random_uuid();

ALTER TABLE account_snapshots
    ALTER COLUMN id SET NOT NULL,
    ADD COLUMN pending_balance NUMERIC(18, 4) NOT NULL DEFAULT 0.0000;

ALTER TABLE account_snapshots
    DROP CONSTRAINT account_snapshots_pkey,
    ADD CONSTRAINT account_snapshots_pkey PRIMARY KEY (id),
    ADD CONSTRAINT uq_account_snapshots_account_id UNIQUE (account_id);

ALTER TABLE journal_entries
    DROP CONSTRAINT journal_entries_status_check,
    ADD CONSTRAINT journal_entries_status_check
        CHECK (status IN ('COMMITTED', 'REJECTED'));
