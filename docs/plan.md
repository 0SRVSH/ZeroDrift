# plan.md

## High-Performance Double-Entry Financial Ledger: Implementation Roadmap

This implementation plan is structured for phased execution by an engineering team or AI code assistant. Each phase specifies concrete tasks, implementation requirements, architectural boundaries, and explicit exit criteria that must pass before advancing to the next phase.

---

### Phase 1: Environment Setup, Containerization & Database Migrations

* **Objective:** Establish the foundational infrastructure, build configuration, and database schema with engine-level parity validation.
* **Tasks:**
  1. Initialize a Spring Boot 3.x project using Java 25 with dependencies: `spring-boot-starter-data-jpa`, `spring-boot-starter-web`, `flyway-core`, `flyway-database-postgresql`, `postgresql`, `redisson-spring-boot-starter`, and `lombok`.
  2. Configure `docker-compose.yml` defining:
     * **PostgreSQL 16** with a dedicated healthcheck and persistent volume.
     * **Redis 7 (Alpine)** for caching and distributed locks.
  3. Author Flyway migration scripts under `src/main/resources/db/migration/`:
     * `V1__init_core_ledger_schema.sql`: Tables for `idempotency_records`, `accounts`, `account_snapshots`, `journal_entries`, and `postings`.
     * `V2__add_debit_credit_parity_trigger.sql`: Plpgsql trigger `trg_postings_verify_parity` (marked `DEFERRABLE INITIALLY DEFERRED`) asserting net debit-credit parity per transaction.
     * `V3__add_performance_indexes.sql`: Composite and partial indexes for queries, range audits, and idempotency checks.
  4. Configure `application.yml` with connection pooling (HikariCP) and Flyway auto-migrate flags enabled.
* **Exit Criteria:**
  * `docker compose up -d` boots both services successfully.
  * Flyway executes cleanly on application boot.
  * Running the SQL assertions in `steps.md` confirms that balanced entries commit and unbalanced entries fail with a parity exception.

---

### Phase 2: Domain Entities, Repositories & Data Access Layer

* **Objective:** Map relational models into Java domain classes without introducing floating-point arithmetic or ORM lock bypasses.
* **Tasks:**
  1. Implement immutable domain records / entities:
     * `Account` (UUID id, accountNumber, currency, accountType, isActive)
     * `AccountSnapshot` (UUID id, accountId, bucketIndex, cleared and pending balances as `BigDecimal`, version, lastJournalEntryId, lastPostingSequence, updatedAt)
     * `JournalEntry` (id, idempotencyKey, narration, status [`COMMITTED`/`REJECTED`], postedAt), created by the application as append-oriented history
     * `Posting` (id, journalEntryId, accountId, bucketIndex, direction [`DEBIT`/`CREDIT`], amount as `BigDecimal`, sequenceOrder), created by the application as append-oriented history
     * `IdempotencyRecord` (idempotencyKey, status [`PENDING`/`COMMITTED`/`REJECTED`], requestHash, JSON responsePayload, createdAt, expiresAt)
  2. Create Spring Data repositories for all five entities. `AccountSnapshotRepository` provides pessimistic locks for individual buckets and a deterministic all-bucket checkpoint lock.
  3. Use explicit scale-4 `BigDecimal` mappings for monetary fields; normalize constructor values with `RoundingMode.UNNECESSARY`.
* **Exit Criteria:**
  * Snapshot monetary fields and constructors use `BigDecimal`; integration tests exercise snapshot balances through transactions and checkpointing.
  * Opposite-direction transfer tests exercise the engine's sorted account UUID lock order and complete without deadlocks.

---

### Phase 3: Core Ledger Engine & Deterministic Concurrency Control

* **Objective:** Implement a balanced, multi-posting ledger pipeline with deterministic locking, non-negative normal balances, and idempotent execution.
* **Tasks:**
  1. Define transaction request and response DTOs for an idempotency key, narration, and at least two account postings.
  2. Validate positive amounts at scale 4 and enforce exact debit-credit parity before persistence.
  3. Implement `LedgerEngineService` with `@Transactional(isolation = Isolation.READ_COMMITTED)`:
     * Sort distinct account UUIDs ascending, then lock required snapshot buckets by ascending bucket index.
     * Verify every referenced account exists, is active, and uses the transaction currency.
     * Apply debit-normal accounting to asset/expense accounts and credit-normal accounting to liability/equity/revenue accounts; reject a negative resulting normal balance.
     * Append a committed journal entry and its postings; update locked snapshots in the same database transaction.
  4. Implement `LedgerOrchestrator` outside the engine transaction:
     * Acquire a Redisson lock scoped to the idempotency key before examining or changing idempotency records.
     * Return a cached response for a matching committed request; reject pending or conflicting key reuse.
     * Save a pending record, invoke the transactional engine, then persist the serialized committed response before releasing the lock.
* **Exit Criteria:**
  * Multi-threaded tests firing transfers in opposite directions complete with **zero deadlocks** and exact balance conservation.
  * Repeated requests with the same idempotency key do not create duplicate journal entries or postings.

---

### Phase 4: Web API Layer

* **Objective:** Expose the ledger pipeline through a resilient REST API.
* **Tasks:**
  1. Implement a controller accepting the general multi-posting transaction DTO and delegating to `LedgerOrchestrator`.
  2. Require `idempotencyKey` in the JSON request body (not an `Idempotency-Key` header); map pending or conflicting key reuse to HTTP `409 Conflict`.
  3. Map validation, balance, and account-state failures to explicit client error responses.
  4. The implemented endpoint is `POST /api/v1/ledger/transactions`; validation requires a nonblank key of at most 128 characters, non-null narration, and at least two individually validated postings.
* **Exit Criteria:**
  * A retry with the same JSON key and request payload returns the cached response without duplicate postings.

---

### Phase 5: High-Concurrency Integration Testing

* **Objective:** Validate system invariants under multithreaded contention without mocking storage layers.
* **Tasks:**
  1. Run the Spring Boot integration tests against the configured PostgreSQL and Redis services; the current test profile does not start Testcontainers.
  2. The current REST integration suite covers balanced and unbalanced transactions, idempotent retries, opposite-direction concurrency, balance depletion, bucketing, and checkpointing.
  3. The concurrent tests use an `ExecutorService` and synchronized request start:
     * **Bidirectional Deadlock Suite:** Concurrent cross-transfers between two accounts; require all requests to complete within a strict timeout and preserve total balance.
     * **Balance Depletion Suite:** 20 threads simultaneously attempting to debit an account holding balance for only 5 transfers; assert exactly 5 succeed and 15 fail with `InsufficientFundsException`.
     * **Network Retry Simulation:** Re-send an identical payload with the same idempotency key and verify the cached response and absence of duplicate journal entries or postings.
* **Exit Criteria:**
  * `mvn test` passes against the configured PostgreSQL and Redis services.
  * Tests create `IT-*` account snapshots directly, without opening postings for their initial balances. Reset the database volumes before a benchmark audit if tests have run since the last clean database.

---

### Phase 6: Hot-Spot Merchant Optimization & Periodic Snapshotting

* **Objective:** Mitigate single-row lock serialization on high-volume recipient accounts.
* **Tasks:**
  1. Implement **Balance Sharding (Bucketing)** for merchant accounts:
     * Split high-throughput destination accounts into $N$ internal balance buckets ($B_1 \dots B_n$).
     * Positive normal-balance adjustments select a bucket at random (`ThreadLocalRandom.current().nextInt(N)`), minimizing lock contention (for example, credits to a liability-type merchant).
  2. Implement **Snapshot Checkpointing Service**:
     * Scheduled task to periodically update `account_snapshots` from the append-only `postings` log.
     * The balance endpoint sums materialized snapshot buckets. The scheduled checkpoint applies only postings whose sequence is newer than each snapshot's `last_posting_sequence`.
     * The load-test fixture configures 20 buckets for `K6-HOTSPOT-MERCHANT`.
* **Exit Criteria:**
  * Benchmark shows reduced lock wait times on hot recipient accounts by $>3\times$ compared to single-row locking.

---

### Phase 7: Benchmarking (k6) & Automated Reconciliation

* **Objective:** Validate the 5,000 TPS target and prove zero balance drift.
* **Tasks:**
  1. Author k6 benchmark scripts under `k6/`:
     * `k6/load-test-uniform.js`: 100 TPS by default on local hardware (20 preallocated / 50 maximum VUs), configurable for a staged 5,000 TPS target across 10,000 retail accounts.
     * `k6/stress-test-hotspot.js`: Heavy-contention traffic funneling to a single merchant.
  2. The run script prepares test accounts, runs k6, and invokes `scripts/reconciliation-audit.ps1`, which executes `scripts/reconciliation-audit.sql`. A local `psql` executable is optional; the scripts fall back to `docker compose exec -T postgres psql`.
  3. The uniform profile's configured thresholds are failure rate below 1%, p95 below 150 ms, and p99 below 300 ms. These thresholds are not measured results.
* **Exit Criteria:**
  * Post-run audit reports zero system drift, snapshot parity discrepancies, and bucket-count discrepancies.
  * Treat 5,000 TPS as a staged/production target; report it as achieved only after measuring that rate with the configured distributed profile.
  * Reset Compose volumes after integration tests and before a clean benchmark run because the `IT-*` fixtures have snapshots without corresponding opening postings.