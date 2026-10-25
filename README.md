# ZeroDrift: High-Performance Double-Entry Financial Ledger

A mission-critical, event-sourced financial ledger built with **Java 25 (Virtual Threads), Spring Boot 3, PostgreSQL 16, and Redis 7**.

Standard databases often drop balances or suffer severe race conditions when concurrent requests debit and credit identical accounts simultaneously. This project uses a double-entry journal as the source of truth and maintains materialized balance snapshots for transactional reads and writes. The application appends journal entries and postings; PostgreSQL validates debit-credit parity at commit time.

---

## 1. Motivation & Industry Context

Modern payment rails and fintech clearing networks (such as Visa, Mastercard, Stripe, and Razorpay) operate on two immutable laws:
1. **Zero Data Loss & Absolute Parity:** Money cannot be created or destroyed out of thin air. For every transfer, total debits must strictly equal total credits.
2. **Idempotent Retry Handling:** Clients may retry after network failures; matching committed requests must not create duplicate ledger writes.

While many web backends rely on naïve `UPDATE accounts SET balance = balance - X` mutations, this engine uses an append-oriented ledger model, database parity validation, deterministic pessimistic concurrency control, and distributed idempotency. A 5,000 TPS run is a staged/production target, not a throughput result established by the local profile.

---

## 2. Core Architecture & Technical Highlights

* **Append-oriented Double-Entry Journal:** Transactions create journal entries and debit/credit postings. The application does not update or delete historical ledger rows; the PostgreSQL parity trigger does not itself prohibit updates or deletes.
* **Storage-Engine Parity Enforcement:** A deferred PostgreSQL constraint trigger (`trg_postings_verify_parity`) validates that each affected journal entry has equal debit and credit totals at transaction commit. It runs after posting inserts or updates.
* **Deterministic Concurrency Control:** The engine sorts distinct account UUIDs in ascending order, then locks the required account snapshot buckets in ascending bucket-index order with `SELECT ... FOR UPDATE`.
* **Bucketed Materialized Balances:** Writers lock snapshot buckets, allocate positive normal-balance adjustments to a selected bucket, and check balance reductions against locked normal balances. The balance endpoint reads all snapshot rows for an account and sums their cleared and pending balances; it does not aggregate postings on each read.
* **Distributed Idempotency:** A Redisson lock scoped to the request's JSON `idempotencyKey` serializes duplicate submissions. Matching committed requests return a cached response; conflicting, pending, or rejected keys receive a conflict response.

---

## 3. Repository Structure

```text
ZeroDrift/
├── docker-compose.yml                  # PostgreSQL 16 and Redis 7
├── docs/                               # Requirements, plan, and implementation steps
├── k6/                                 # k6 load and stress benchmark scripts
├── scripts/                            # Load-test account setup and reconciliation
├── src/
│   ├── main/
│   │   ├── java/com/ledger/
│   │   │   ├── LedgerApplication.java
│   │   │   ├── controller/             # Ledger REST endpoints
│   │   │   ├── config/                 # Application configuration and sample data
│   │   │   ├── domain/                 # Accounts, journal entries, and postings
│   │   │   ├── repository/             # Spring Data repositories
│   │   │   └── service/                # Validation, posting, and snapshot services
│   │   └── resources/
│   │       ├── application.yml         # Runtime configuration
│   │       └── db/migration/           # Flyway SQL migrations
│   └── test/java/com/ledger/            # Integration and concurrency tests
└── pom.xml                             # Maven build configuration
```

---

## 4. Architectural Assumptions & Domain Design

* **Currency Precision:** Currency values are never represented using floating-point types (`float`, `double`). All monetary amounts use Java `BigDecimal` (fixed scale of 4 decimal places) and PostgreSQL `NUMERIC(18, 4)` to eliminate IEEE-754 precision drift.
* **Lock Scoping:** Redisson locks wrap the transactional ledger engine call and are released after it returns (after its transaction commits). Account snapshot locks are acquired in ascending account UUID and bucket-index order.
* **Auditing Over Deletion:** The application creates new offsetting transactions for corrections rather than deleting historical rows. The current schema does not store an explicit reversal-to-original-entry reference.

---

## 5. Local Development

### Prerequisites
* Java 25 SDK (with Virtual Thread support enabled)
* Maven 3.9+
* Docker & Docker Compose

### 1. Start PostgreSQL and Redis
```powershell
docker compose up -d
```

Flyway applies the schema migrations automatically when the application starts.
Database defaults are `ledger_db` / `postgres` / `postgres`; override them with
`DB_NAME`, `DB_USER`, `DB_PASSWORD`, `DB_HOST`, and `DB_PORT` when needed.

### 2. Compile, test, and run
From PowerShell:
```powershell
.\mvnw.cmd clean compile
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

The integration tests require PostgreSQL and Redis to be running; Docker Compose
provides both. The server starts on port `8080`. If the database contains no
accounts, startup creates an active USD operating asset and revenue account with
zero balances and logs their account IDs.

### 3. Ledger API

* `POST /api/v1/ledger/transactions` posts a balanced transaction.
* `GET /api/v1/ledger/accounts/{accountId}/balance` returns the sum of that account's snapshot buckets.
* `GET /api/v1/ledger/transactions/{id}` returns a journal entry and its ordered postings.

`POST /api/v1/ledger/transactions` accepts a JSON body with an `idempotencyKey`,
`narration`, and at least two postings. The key is **not read from an HTTP header**:
the controller binds the body to `PostTransactionRequest` and does not declare an
`Idempotency-Key` header parameter. Each posting requires a UUID `accountId`, a
`DEBIT` or `CREDIT` `direction`, and a positive amount with at most 14 integer
digits and 4 fractional digits. The posting amounts must balance exactly.
Referenced accounts must exist, be active, and share one currency.

The request validation rules are:

* `idempotencyKey`: nonblank, at most 128 characters.
* `narration`: non-null; an empty string is allowed.
* `postings`: non-null, at least two entries; each entry is validated.
* Each posting: non-null `accountId` and `direction`; non-null positive `amount`
  satisfying `@Digits(integer = 14, fraction = 4)`.

For example, the following body uses placeholder account UUIDs and must be
replaced with active accounts of the same currency:

```powershell
curl.exe -X POST 'http://localhost:8080/api/v1/ledger/transactions' `
  -H 'Content-Type: application/json' `
  -d '{"idempotencyKey":"client-order-123","narration":"Example transfer","postings":[{"accountId":"00000000-0000-0000-0000-000000000001","direction":"DEBIT","amount":"10.0000"},{"accountId":"00000000-0000-0000-0000-000000000002","direction":"CREDIT","amount":"10.0000"}]}'
```

Bean-validation errors return HTTP 400; unbalanced postings and insufficient
cleared funds return HTTP 422; idempotency conflicts return HTTP 409; missing
accounts or transactions return HTTP 404. Successful transaction creation returns
HTTP 201. The idempotency record schema permits `PENDING`, `COMMITTED`, and
`REJECTED`, while the current successful request flow creates `PENDING` and then
marks it `COMMITTED`. If ledger processing fails after the pending record is
saved, the current code leaves it `PENDING`; a retry then receives HTTP 409.
Although the row's `expires_at` is set to one day, there is no cleanup job, so
neither pending records nor their keys are automatically expired or released.

## 6. Architecture and Invariants

* **Zero drift:** Every journal entry must balance. A deferred PostgreSQL constraint
  trigger rejects unbalanced postings at commit; amounts use fixed-scale
  `NUMERIC(18, 4)`.
* **Deterministic pessimistic locking:** The engine sorts account UUIDs ascending,
  then locks affected bucket rows in ascending bucket order. Balance-reducing
  adjustments lock every bucket for that account and consume its available normal
  balance in bucket order.
  Positive normal-balance adjustments select a bucket at random (for example,
  credits to the liability-type hotspot merchant).
* **Balance bucketing and checkpointing:** Load-test setup creates a hotspot
  merchant with 20 buckets. `SnapshotCheckpointService` runs every 60 seconds by
  default and applies posting deltas after each snapshot's last posting sequence.
  The API balance read simply sums the account's materialized snapshot rows.
* **Snapshot reconciliation:** The audit derives cleared balances from postings
  using each account type's normal-balance convention and compares them with the
  sum of all corresponding snapshot buckets.

## 7. Performance Benchmarking (k6)

Run these commands from the repository root. Install k6 using the official
[k6 installation instructions](https://grafana.com/docs/k6/latest/set-up/install-k6/).
The scripts require `k6`, Docker Compose, PostgreSQL, Redis, and the running Spring
Boot application. A local `psql` executable is optional: both PowerShell database
scripts use it when present and otherwise pipe SQL to
`docker compose exec -T postgres psql`.

The setup script creates or reuses 10,000 `K6-RETAIL-*` asset accounts, one
`K6-FUNDING-EQUITY` account, and one `K6-HOTSPOT-MERCHANT` liability account with
20 buckets. It records balanced $1,000,000 opening journal entries for the retail
accounts and returns the account IDs. The fixture is written to
`k6\load-test-accounts.json`; the setup is reused on later runs.

**Local hardware profile:** uniform load defaults to 100 transactions/second with
20 preallocated and at most 50 VUs for 30 seconds. Use `-Rate 200` to request the
upper end of the 100–200 TPS local profile. The hotspot profile defaults to 50
constant VUs; this is a contention scenario, not a fixed TPS profile:
```powershell
.\scripts\run-load-test.ps1 -Scenario uniform
.\scripts\run-load-test.ps1 -Scenario uniform -Rate 200
.\scripts\run-load-test.ps1 -Scenario hotspot -Duration 30s -VirtualUsers 50
```

**Staged/production target:** the uniform script can be configured for 5,000
transactions/second with a larger VU allocation. This distributed target is not
evidence that a single developer machine can sustain that rate. Staging also
requires capacity planning for the application instances, `DB_POOL_MAX_SIZE`,
PostgreSQL, and distributed k6 load generators:
```powershell
.\scripts\run-load-test.ps1 -Scenario uniform -Duration 30s -Rate 5000 -PreAllocatedVUs 1000 -MaxVUs 5000
.\scripts\run-load-test.ps1 -Scenario hotspot -Duration 30s -VirtualUsers 5000
```

The uniform k6 profile enforces a request failure rate below 1%, p95 latency
below 150 ms, and p99 latency below 300 ms. These are configured thresholds, not
reported benchmark results. The hotspot profile enforces a failure rate below 1%
and has no latency threshold. The run script prepares the account pool, runs k6
against `http://localhost:8080` by default, and runs reconciliation even if k6
fails. Parameters are `-Scenario`, `-Duration`, `-Rate`, `-PreAllocatedVUs`,
`-MaxVUs`, `-VirtualUsers`, `-BaseUrl`, `-DatabaseHost`, `-DatabasePort`,
`-DatabaseName`, and `-DatabaseUser`. Set `DB_HOST`, `DB_PORT`, `DB_NAME`,
`DB_USER`, and `DB_PASSWORD` in the PowerShell environment to configure the
database defaults. A non-default host or port requires a local `psql` client;
the Compose fallback uses the `postgres` service and ignores host/port parameters.

To run the load and audit separately:
```powershell
.\scripts\prepare-load-test.ps1
$env:ACCOUNT_POOL_FILE = (Resolve-Path .\k6\load-test-accounts.json).Path
k6 run --env "ACCOUNT_POOL_FILE=$env:ACCOUNT_POOL_FILE" --env BASE_URL=http://localhost:8080 --env DURATION=30s --env RATE=100 .\k6\load-test-uniform.js
.\scripts\reconciliation-audit.ps1
```

The wrapper passes its scenario settings to k6 with explicit `--env` arguments.
When invoking k6 directly, use the same option for each value that the script
reads from `__ENV`; setting a PowerShell environment variable alone is not enough.

The audit runs [reconciliation-audit.sql](./scripts/reconciliation-audit.sql) and
fails if global debit-minus-credit drift is nonzero, if any account's sum of
cleared snapshot balances differs from its posting-derived normal balance, or if
its snapshot row count differs from `accounts.bucket_count`. It reports up to ten
sample account IDs on snapshot parity failure. The SQL does not compare pending
balances, validate `last_posting_sequence`, or check parity separately per
journal entry; the deferred trigger performs per-entry parity checks for posting
inserts and updates.

`reconciliation-audit.ps1` accepts `-DatabaseHost`, `-DatabasePort`,
`-DatabaseName`, and `-DatabaseUser`, defaulting to `DB_HOST`, `DB_PORT`,
`DB_NAME`, and `DB_USER` or `localhost:5432/ledger_db` with user `postgres`.
It uses a local `psql` if available (setting its password from `DB_PASSWORD` or
the default `postgres`), otherwise runs the SQL through
`docker compose exec -T postgres psql`. Both paths enable `ON_ERROR_STOP`; a
nonzero `psql` exit becomes a script error. The Compose fallback connects to the
`postgres` service and uses the database name/user, rather than the host/port
parameters.

**Clean database prerequisite:** the Phase 5/6 integration tests create `IT-*`
accounts and nonzero initial `account_snapshots` directly, without matching
opening postings. Those test fixtures therefore fail snapshot reconciliation.
After running the integration tests, reset the Compose volumes before preparing
and running the benchmark. This permanently deletes the local PostgreSQL and Redis
volume data, so back up anything you need first:

```bash
docker compose down -v && docker compose up -d
```

Wait for PostgreSQL and Redis to become healthy, start the Spring Boot application
so Flyway can apply migrations, then run `run-load-test.ps1`. Do not rerun the
integration tests between the volume reset and the benchmark audit.

## 8. Key References

* **Martin Fowler:** *Accounting Patterns & Event Sourcing*
* **TigerBeetle Design Philosophy:** *Safety and Financial Invariants in High-Throughput Storage Engines*
* **Stripe Engineering Blog:** *Designing Robust and Idempotent APIs for Payment Systems*