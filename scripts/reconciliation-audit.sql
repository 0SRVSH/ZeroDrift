DO $$
DECLARE
    system_drift NUMERIC(18, 4);
    snapshot_anomaly_count BIGINT;
    snapshot_anomaly_accounts TEXT;
BEGIN
    SELECT COALESCE(
        SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END),
        0.0000
    )::NUMERIC(18, 4)
    INTO system_drift
    FROM postings;

    RAISE NOTICE 'System drift: %', system_drift;
    IF system_drift <> 0.0000::NUMERIC THEN
        RAISE EXCEPTION 'System drift invariant failed: expected 0.0000, got %', system_drift;
    END IF;

    WITH snapshot_balances AS (
        SELECT account_id, COUNT(*) AS bucket_count, SUM(cleared_balance) AS cleared_balance
        FROM account_snapshots
        GROUP BY account_id
    ),
    derived_balances AS (
        SELECT posting.account_id,
               SUM(
                   CASE
                       WHEN (account.account_type IN ('ASSET', 'EXPENSE')
                             AND posting.direction = 'DEBIT')
                         OR (account.account_type IN ('LIABILITY', 'EQUITY', 'REVENUE')
                             AND posting.direction = 'CREDIT')
                       THEN posting.amount
                       ELSE -posting.amount
                   END
               ) AS derived_balance
        FROM postings AS posting
        JOIN accounts AS account ON account.id = posting.account_id
        GROUP BY posting.account_id
    ),
    discrepancies AS (
        SELECT account.id, account.bucket_count AS expected_bucket_count,
               COALESCE(snapshot.bucket_count, 0) AS actual_bucket_count,
               COALESCE(snapshot.cleared_balance, 0.0000) AS cleared_balance,
               COALESCE(derived.derived_balance, 0.0000) AS derived_balance
        FROM accounts AS account
        LEFT JOIN snapshot_balances AS snapshot ON snapshot.account_id = account.id
        LEFT JOIN derived_balances AS derived ON derived.account_id = account.id
        WHERE COALESCE(snapshot.bucket_count, 0) <> account.bucket_count
           OR COALESCE(snapshot.cleared_balance, 0.0000)
              <> COALESCE(derived.derived_balance, 0.0000)
    )
    SELECT (SELECT COUNT(*) FROM discrepancies),
           (
               SELECT STRING_AGG(id::text, ', ' ORDER BY id)
               FROM (
                   SELECT id
                   FROM discrepancies
                   ORDER BY id
                   LIMIT 10
               ) AS sample
           )
    INTO snapshot_anomaly_count, snapshot_anomaly_accounts;

    IF snapshot_anomaly_count > 0 THEN
        RAISE EXCEPTION 'Snapshot parity invariant failed for % account(s); sample account IDs: %',
            snapshot_anomaly_count, snapshot_anomaly_accounts;
    END IF;

    RAISE NOTICE 'Snapshot parity: no discrepancies across all accounts';
END;
$$;
