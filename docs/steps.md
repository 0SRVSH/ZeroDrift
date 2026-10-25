# steps.md

This document provides a step-by-step setup and execution guide for configuring Flyway database migrations in a Java 21 / Spring Boot 3 financial ledger application, ensuring immutable double-entry records, idempotency tracking, and storage-level debit-credit parity enforcement[cite: 1].

---

### Step 1: Directory Layout & Dependencies

Establish the standard Flyway migration folder hierarchy under `src/main/resources`:

```text
src/main/resources/
└── db/
    └── migration/
        ├── V1__init_core_ledger_schema.sql
        ├── V2__add_debit_credit_parity_trigger.sql
        └── V3__add_performance_indexes.sql
```

Add PostgreSQL and Flyway dependencies to `pom.xml`:

```xml
<dependencies>
    <!-- Spring Data JPA / JDBC Abstraction -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>

    <!-- PostgreSQL Driver -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>

    <!-- Flyway Core Engine & PostgreSQL Support -->
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-database-postgresql</artifactId>
    </dependency>
</dependencies>
```

Add database and migration properties to `src/main/resources/application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:ledger_db}
    username: ${DB_USER:postgres}
    password: ${DB_PASSWORD:postgres}
    driver-class-name: org.postgresql.Driver
  flyway:
    enabled: true
    baseline-on-migrate: true
    locations: classpath:db/migration
    validate-on-migrate: true
```

---

### Step 2: Create `V1__init_core_ledger_schema.sql`

Create `src/main/resources/db/migration/V1__init_core_ledger_schema.sql` to provision the core domain tables.

```sql
-- 1. Idempotency State Machine Table
-- Guarantees atomic request deduplication across retries[cite: 1]
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
-- Serves as the target for row-level locks (SELECT ... FOR UPDATE) to avoid scanning historical postings[cite: 1]
CREATE TABLE account_snapshots (
    account_id UUID PRIMARY KEY REFERENCES accounts(id) ON DELETE RESTRICT,
    cleared_balance NUMERIC(18, 4) NOT NULL DEFAULT 0.0000,
    version BIGINT NOT NULL DEFAULT 0,
    last_journal_entry_id UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 4. Journal Entries Table
-- Encapsulates a distinct business transaction linking balanced posting legs[cite: 1]
CREATE TABLE journal_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(128) UNIQUE NOT NULL REFERENCES idempotency_records(idempotency_key) ON DELETE RESTRICT,
    narration TEXT NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('POSTED', 'VOIDED')),
    posted_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 5. Postings Table
-- Immutable double-entry legs; modifications and deletions are prohibited[cite: 1]
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
```

---

### Step 3: Create `V2__add_debit_credit_parity_trigger.sql`

Create `src/main/resources/db/migration/V2__add_debit_credit_parity_trigger.sql` to enforce 100% debit-credit parity at transaction commit time[cite: 1].

```sql
-- Evaluates net sum of debits and credits for the affected journal entry
CREATE OR REPLACE FUNCTION verify_journal_entry_parity()
RETURNS TRIGGER AS $$ DECLARE net_discrepancy NUMERIC(18, 4); BEGIN SELECT COALESCE( SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END), 0 ) INTO net_discrepancy FROM postings WHERE journal_entry_id = NEW.journal_entry_id;      IF net_discrepancy <> 0 THEN RAISE EXCEPTION 'Double-entry parity violation: Journal \% does not balance. Net discrepancy: \%', NEW.journal_entry_id, net_discrepancy; END IF;      RETURN NEW; END; $$ LANGUAGE plpgsql;

-- Constraint trigger deferred to transaction commit time
CREATE CONSTRAINT TRIGGER trg_postings_verify_parity
AFTER INSERT OR UPDATE ON postings
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION verify_journal_entry_parity();
```

---

### Step 4: Create `V3__add_performance_indexes.sql`

Create `src/main/resources/db/migration/V3__add_performance_indexes.sql` to index lookup paths under high concurrency.

```sql
-- Accelerates historical balance lookups and account auditing[cite: 1]
CREATE INDEX idx_postings_account_created_at 
ON postings (account_id, created_at DESC);

-- Fast lookup for all legs of a given journal entry
CREATE INDEX idx_postings_journal_id 
ON postings (journal_entry_id);

-- Speeds up lookup for in-flight requests and background expiry routines[cite: 1]
CREATE INDEX idx_idempotency_expires_at 
ON idempotency_records (expires_at) 
WHERE status = 'PENDING';

-- Accelerates chronological auditing
CREATE INDEX idx_journal_entries_posted_at 
ON journal_entries (posted_at DESC);
```

---

### Step 5: Migration Execution & Invariant Verification

#### 1. Spin Up Local PostgreSQL Container
```bash
docker run --name ledger-postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=ledger_db \
  -p 5432:5432 \
  -d postgres:16-alpine
```

#### 2. Execute Flyway Migrations
Run Flyway through Maven:
```bash
./mvnw flyway:migrate
```

Confirm all scripts have been applied:
```sql
SELECT version, description, type, installed_on, success 
FROM flyway_schema_history 
ORDER BY installed_rank;
```

#### 3. Verification Test A: Valid Balanced Transfer
Execute inside `psql` to verify valid inserts:
```sql
BEGIN;

INSERT INTO idempotency_records (idempotency_key, status, request_hash, expires_at)
VALUES ('test-key-01', 'PENDING', 'hash_01', NOW() + INTERVAL '1 hour');

INSERT INTO accounts (id, account_number, currency, account_type) VALUES
    ('a0000000-0000-0000-0000-000000000001', 'ACC_DEBIT', 'USD', 'ASSET'),
    ('b0000000-0000-0000-0000-000000000002', 'ACC_CREDIT', 'USD', 'LIABILITY');

INSERT INTO journal_entries (id, idempotency_key, narration, status)
VALUES ('c0000000-0000-0000-0000-000000000003', 'test-key-01', 'Balanced Leg Test', 'POSTED');

-- Balanced Postings ($150.00 DEBIT and $150.00 CREDIT)[cite: 1]
INSERT INTO postings (journal_entry_id, account_id, direction, amount, sequence_order) VALUES
    ('c0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000001', 'DEBIT', 150.0000, 0),
    ('c0000000-0000-0000-0000-000000000003', 'b0000000-0000-0000-0000-000000000002', 'CREDIT', 150.0000, 1);

COMMIT;
-- Expected output: COMMIT (Success)
```

#### 4. Verification Test B: Unbalanced Transfer Rejection
Execute an unbalanced insert to verify the trigger aborts commit:
```sql
BEGIN;

INSERT INTO idempotency_records (idempotency_key, status, request_hash, expires_at)
VALUES ('test-key-02', 'PENDING', 'hash_02', NOW() + INTERVAL '1 hour');

INSERT INTO journal_entries (id, idempotency_key, narration, status)
VALUES ('d0000000-0000-0000-0000-000000000004', 'test-key-02', 'Unbalanced Leg Test', 'POSTED');

-- Intentionally Unbalanced ($150.00 DEBIT vs $100.00 CREDIT)
INSERT INTO postings (journal_entry_id, account_id, direction, amount, sequence_order) VALUES
    ('d0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000001', 'DEBIT', 150.0000, 0),
    ('d0000000-0000-0000-0000-000000000004', 'b0000000-0000-0000-0000-000000000002', 'CREDIT', 100.0000, 1);

COMMIT;
-- Expected output: ERROR: Double-entry parity violation: Journal d0000000-0000-0000-0000-000000000004 does not balance. Net discrepancy: 50.0000
```