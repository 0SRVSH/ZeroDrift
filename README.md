# ZeroDrift: High-Performance Double-Entry Financial Ledger

A mission-critical, event-sourced financial ledger built with **Java 25 (Virtual Threads), Spring Boot 3, PostgreSQL 16, and Redis 7**.

Standard databases often drop balances or suffer severe race conditions when concurrent requests debit and credit identical accounts simultaneously. This project provides a robust, zero-drift alternative: an immutable double-entry bookkeeping system where accounts maintain no direct mutable balance; balances are derived strictly from an immutable stream of balanced debits and credits.

---

## 1. Motivation & Industry Context

Modern payment rails and fintech clearing networks (such as Visa, Mastercard, Stripe, and Razorpay) operate on two immutable laws:
1. **Zero Data Loss & Absolute Parity:** Money cannot be created or destroyed out of thin air. For every transfer, total debits must strictly equal total credits.
2. **Exact-Once Processing Under Network Failure:** Unreliable networks guarantee that clients will retry transactions. Double charges and negative-balance race conditions are catastrophic failure modes that cannot be allowed.

While many web backends rely on naïve `UPDATE accounts SET balance = balance - X` mutations, this engine treats balance mutation as an anti-pattern. Instead, it uses an event-driven ledger model with database-enforced integrity checks, deterministic pessimistic concurrency control, and distributed idempotency state machines to sustain high throughput (5,000 TPS) without data corruption.

---

## 2. Core Architecture & Technical Highlights

* **Immutable Double-Entry Bookkeeping:** All monetary events are append-only journal entries comprising discrete debit and credit posting legs. Direct updates and deletions on ledger tables are prohibited.
* **Storage-Engine Parity Enforcement:** A deferred PostgreSQL trigger (`trg_postings_verify_parity`) validates that $\sum \text{Debits} - \sum \text{Credits} \equiv 0$ prior to transaction commit. Unbalanced writes are rejected directly at the database engine level.
* **Deterministic Concurrency Control:** Prevents cyclic deadlocks and negative balances during concurrent multi-party transfers by locking accounts in lexicographical order via `SELECT ... FOR UPDATE`.
* **Materialized Snapshot Balances:** Solves the derived balance table-scan bottleneck by combining point-in-time balance snapshots with bounded posting deltas, avoiding full table scans during high-throughput workloads.
* **Distributed Idempotency Pipeline:** Exposes an idempotent transfer API using client-provided idempotency keys mapped to an atomic write status machine (`PENDING`, `COMMITTED`, `REJECTED`) combined with Redis distributed locks to eliminate replay attacks and duplicate debits.

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
* **Lock Scoping:** Redis distributed locks strictly wrap the outer lifecycle of the database transaction and are released only *after* PostgreSQL acknowledges disk commit, preventing dirty reads across application nodes.
* **Deterministic Ordering:** Any operation affecting multiple accounts sorts target account identifiers ascending (`ORDER BY account_id ASC`) before acquiring pessimistic locks, eliminating database deadlocks.
* **Auditing Over Deletion:** Voided or adjusted transactions are never deleted from history. Reversals are modeled as explicit offsetting posting legs referencing the original journal entry.

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
* `GET /api/v1/ledger/accounts/{accountId}/balance` returns the account snapshot.
* `GET /api/v1/ledger/transactions/{id}` returns a journal entry and its ordered postings.

Each transaction request requires a unique `idempotencyKey`, a `narration`, and
at least two postings with equal debit and credit totals. A posting contains an
`accountId`, a `direction` (`DEBIT` or `CREDIT`), and a positive amount with up to
four decimal places.

## 6. Architecture and Invariants

* **Zero drift:** Every journal entry must balance. A deferred PostgreSQL constraint
  trigger rejects unbalanced postings at commit; amounts use fixed-scale
  `NUMERIC(18, 4)`.
* **Deterministic pessimistic locking:** A transaction sorts its account IDs before
  locking snapshot rows with `SELECT ... FOR UPDATE`, preventing lock-order deadlocks.
  Redis locks serialize requests that share an idempotency key.
* **Balance sharding:** An account can use multiple snapshot buckets. Positive
  balance adjustments select a bucket, distributing credits for high-volume
  merchant accounts; balance reads aggregate the account's buckets.
* **Snapshot reconciliation:** Snapshot balances are checked against all postings
  using the normal-balance convention for each account type.

## 7. Performance Benchmarking (k6)

Install k6 using the official
[k6 installation instructions](https://grafana.com/docs/k6/latest/set-up/install-k6/).
The scripts require `k6` and PostgreSQL's `psql` client on `PATH`. Start Docker
Compose and the Spring Boot application before benchmarking. The setup script
creates 10,000 load-test asset accounts with balanced opening postings and a
liability merchant with 20 buckets. Those records are retained and reused by
subsequent runs.

Run the 30-second, 5,000-request-per-second uniform workload and reconcile after it:
```powershell
.\scripts\run-load-test.ps1 -Scenario uniform
```

Run the hotspot test with 5,000 virtual users crediting the same merchant:
```powershell
.\scripts\run-load-test.ps1 -Scenario hotspot -Duration 30s -VirtualUsers 5000
```

The uniform test enforces an HTTP request failure rate below `0.01%`, p95 latency
below `50ms`, and p99 latency below `100ms`. The run script prepares or reuses its
account pool, runs k6 against `http://localhost:8080`, then runs reconciliation
even if the benchmark reports a failure. Adjust the run with `-Rate`, `-Duration`,
`-VirtualUsers`, and `-BaseUrl`. Set `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`,
and `DB_PASSWORD` in the PowerShell environment to target a non-default database.

To run the load and audit separately:
```powershell
.\scripts\prepare-load-test.ps1
$env:ACCOUNT_POOL_FILE = Join-Path $env:TEMP 'zerodrift-load-test-accounts.json'
$env:BASE_URL = 'http://localhost:8080'
k6 run .\k6\load-test-uniform.js
.\scripts\reconciliation-audit.ps1
```

The audit executes [reconciliation-audit.sql](./scripts/reconciliation-audit.sql)
and exits with an error if total debit-credit drift is non-zero, any account's
aggregate cleared snapshot differs from derived postings, or any account has an
unexpected number of snapshot buckets. Since the load accounts' opening balances
are represented by balanced journal entries, they are included in both sides of
the audit.

## 8. Key References

* **Martin Fowler:** *Accounting Patterns & Event Sourcing*
* **TigerBeetle Design Philosophy:** *Safety and Financial Invariants in High-Throughput Storage Engines*
* **Stripe Engineering Blog:** *Designing Robust and Idempotent APIs for Payment Systems*