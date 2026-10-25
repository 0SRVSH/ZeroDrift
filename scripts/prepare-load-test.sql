BEGIN;

INSERT INTO accounts (account_number, currency, account_type, is_active, bucket_count)
SELECT 'K6-RETAIL-' || LPAD(retail_number::text, 5, '0'), 'USD', 'ASSET', TRUE, 1
FROM GENERATE_SERIES(1, 10000) AS retail_number
ON CONFLICT (account_number) DO UPDATE
SET currency = EXCLUDED.currency,
    account_type = EXCLUDED.account_type,
    is_active = EXCLUDED.is_active,
    bucket_count = EXCLUDED.bucket_count;

INSERT INTO accounts (account_number, currency, account_type, is_active, bucket_count)
VALUES ('K6-FUNDING-EQUITY', 'USD', 'EQUITY', TRUE, 1)
ON CONFLICT (account_number) DO UPDATE
SET currency = EXCLUDED.currency,
    account_type = EXCLUDED.account_type,
    is_active = EXCLUDED.is_active,
    bucket_count = EXCLUDED.bucket_count;

INSERT INTO accounts (account_number, currency, account_type, is_active, bucket_count)
VALUES ('K6-HOTSPOT-MERCHANT', 'USD', 'LIABILITY', TRUE, 20)
ON CONFLICT (account_number) DO UPDATE
SET currency = EXCLUDED.currency,
    account_type = EXCLUDED.account_type,
    is_active = EXCLUDED.is_active,
    bucket_count = EXCLUDED.bucket_count;

INSERT INTO account_snapshots (
    account_id, bucket_index, cleared_balance, pending_balance, version,
    last_posting_sequence, updated_at
)
SELECT id, 0, 0.0000, 0.0000, 0, 0, NOW()
FROM accounts
WHERE account_number LIKE 'K6-RETAIL-%'
   OR account_number = 'K6-FUNDING-EQUITY'
ON CONFLICT (account_id, bucket_index) DO NOTHING;

INSERT INTO account_snapshots (
    account_id, bucket_index, cleared_balance, pending_balance, version,
    last_posting_sequence, updated_at
)
SELECT merchant.id, bucket.bucket_index, 0.0000, 0.0000, 0, 0, NOW()
FROM accounts AS merchant
CROSS JOIN GENERATE_SERIES(0, 19) AS bucket(bucket_index)
WHERE merchant.account_number = 'K6-HOTSPOT-MERCHANT'
ON CONFLICT (account_id, bucket_index) DO NOTHING;

INSERT INTO idempotency_records (
    idempotency_key, status, request_hash, created_at, updated_at, expires_at
)
SELECT 'K6-OPENING-' || retail.id::text, 'COMMITTED', REPEAT('0', 64), NOW(), NOW(),
       NOW() + INTERVAL '365 days'
FROM accounts AS retail
WHERE retail.account_number LIKE 'K6-RETAIL-%'
ON CONFLICT (idempotency_key) DO NOTHING;

INSERT INTO journal_entries (idempotency_key, narration, status, posted_at)
SELECT 'K6-OPENING-' || retail.id::text,
       'k6 load-test opening balance',
       'COMMITTED',
       NOW()
FROM accounts AS retail
WHERE retail.account_number LIKE 'K6-RETAIL-%'
  AND NOT EXISTS (
      SELECT 1
      FROM journal_entries AS existing
      WHERE existing.idempotency_key = 'K6-OPENING-' || retail.id::text
  );

INSERT INTO postings (
    journal_entry_id, account_id, bucket_index, direction, amount, sequence_order
)
SELECT entry.id, retail.id, 0, 'DEBIT', 1000000.0000, 0
FROM accounts AS retail
JOIN journal_entries AS entry
  ON entry.idempotency_key = 'K6-OPENING-' || retail.id::text
WHERE retail.account_number LIKE 'K6-RETAIL-%'
  AND NOT EXISTS (
      SELECT 1
      FROM postings AS existing
      WHERE existing.journal_entry_id = entry.id
  )
UNION ALL
SELECT entry.id, funding.id, 0, 'CREDIT', 1000000.0000, 1
FROM accounts AS retail
JOIN journal_entries AS entry
  ON entry.idempotency_key = 'K6-OPENING-' || retail.id::text
JOIN accounts AS funding
  ON funding.account_number = 'K6-FUNDING-EQUITY'
WHERE retail.account_number LIKE 'K6-RETAIL-%'
  AND NOT EXISTS (
      SELECT 1
      FROM postings AS existing
      WHERE existing.journal_entry_id = entry.id
  );

WITH derived_balances AS (
    SELECT posting.account_id,
           posting.bucket_index,
           SUM(
               CASE
                   WHEN (account.account_type IN ('ASSET', 'EXPENSE')
                         AND posting.direction = 'DEBIT')
                     OR (account.account_type IN ('LIABILITY', 'EQUITY', 'REVENUE')
                         AND posting.direction = 'CREDIT')
                   THEN posting.amount
                   ELSE -posting.amount
               END
           ) AS balance,
           MAX(posting.posting_sequence) AS last_posting_sequence
    FROM postings AS posting
    JOIN accounts AS account ON account.id = posting.account_id
    WHERE account.account_number LIKE 'K6-RETAIL-%'
       OR account.account_number = 'K6-FUNDING-EQUITY'
    GROUP BY posting.account_id, posting.bucket_index
)
UPDATE account_snapshots AS snapshot
SET cleared_balance = COALESCE(derived.balance, 0.0000),
    last_posting_sequence = COALESCE(derived.last_posting_sequence, 0),
    updated_at = NOW()
FROM accounts AS account
LEFT JOIN derived_balances AS derived
  ON derived.account_id = account.id
 AND derived.bucket_index = 0
WHERE snapshot.account_id = account.id
  AND snapshot.bucket_index = 0
  AND (account.account_number LIKE 'K6-RETAIL-%'
       OR account.account_number = 'K6-FUNDING-EQUITY');

COMMIT;

SELECT JSON_BUILD_OBJECT(
    'retailAccountIds',
    (
        SELECT JSON_AGG(id::text ORDER BY account_number)
        FROM accounts
        WHERE account_number LIKE 'K6-RETAIL-%'
    ),
    'hotspotMerchantId',
    (
        SELECT id::text
        FROM accounts
        WHERE account_number = 'K6-HOTSPOT-MERCHANT'
    )
)::text;
