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
     * `AccountSnapshot` (UUID id and accountId, cleared and pending balances as `BigDecimal`, version, lastJournalEntryId, updatedAt)
     * `JournalEntry` (id, idempotencyKey, narration, status [`COMMITTED`/`REJECTED`], postedAt), immutable and append-only
     * `Posting` (id, journalEntryId, accountId, direction [`DEBIT`/`CREDIT`], amount as `BigDecimal`, sequenceOrder), immutable and append-only
     * `IdempotencyRecord` (idempotencyKey, status [`PENDING`/`COMMITTED`/`REJECTED`], requestHash, JSON responsePayload, createdAt, expiresAt)
  2. Create Spring Data repositories for all five entities. `AccountSnapshotRepository` must provide an ordered pessimistic write-lock query by account ID.
  3. Use explicit scale-4 `BigDecimal` mappings for monetary fields; normalize constructor values with `RoundingMode.UNNECESSARY`.
* **Exit Criteria:**
  * Unit tests confirm `AccountSnapshot` amounts cannot be instantiated using `Double` or `Float`.
  * Repository tests verify that `lockAccountsForUpdate` returns accounts in strictly sorted ascending order.

---

### Phase 3: Core Ledger Engine & Deterministic Concurrency Control

* **Objective:** Implement a balanced, multi-posting ledger pipeline with deterministic locking, non-negative normal balances, and idempotent execution.
* **Tasks:**
  1. Define transaction request and response DTOs for an idempotency key, narration, and at least two account postings.
  2. Validate positive amounts at scale 4 and enforce exact debit-credit parity before persistence.
  3. Implement `LedgerEngineService` with `@Transactional(isolation = Isolation.READ_COMMITTED)`:
     * Sort distinct account UUIDs ascending before locking snapshots.
     * Verify every referenced account exists, is active, and uses the transaction currency.
     * Apply debit-normal accounting to asset/expense accounts and credit-normal accounting to liability/equity/revenue accounts; reject a negative resulting normal balance.
     * Append a committed journal entry and its immutable postings; update the locked snapshots and their optimistic versions.
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
  2. Enforce the required idempotency key at the HTTP boundary; map pending or conflicting key reuse to HTTP `409 Conflict`.
  3. Map validation, balance, and account-state failures to explicit client error responses.
  4. Implement the transaction endpoint under `/api/v1/transactions`.
* **Exit Criteria:**
  * Firing 20 concurrent requests with an identical `Idempotency-Key` results in one committed transaction and no duplicate postings.

---

### Phase 5: High-Concurrency Integration Testing (Testcontainers)

* **Objective:** Validate system invariants under multithreaded contention without mocking storage layers.
* **Tasks:**
  1. Configure `Testcontainers` for PostgreSQL 16 and Redis 7 in `src/test/java`.
  2. Implement multithreaded test suites using `CompletableFuture` / `ExecutorService`:
     * **Bidirectional Deadlock Suite:** 50 threads executing cross-transfers concurrently between Account 1 and Account 2.
     * **Balance Depletion Suite:** 20 threads simultaneously attempting to debit an account holding balance for only 5 transfers; assert exactly 5 succeed and 15 fail with `InsufficientFundsException`.
     * **Network Retry Simulation:** Simulate client timeouts by re-sending identical payload with the same idempotency key across different threads.
* **Exit Criteria:**
  * `mvn clean verify` passes with 100% test success across all concurrent scenarios.

---

### Phase 6: Hot-Spot Merchant Optimization & Periodic Snapshotting

* **Objective:** Mitigate single-row lock serialization on high-volume recipient accounts.
* **Tasks:**
  1. Implement **Balance Sharding (Bucketing)** for merchant accounts:
     * Split high-throughput destination accounts into $N$ internal balance buckets ($B_1 \dots B_n$).
     * Credit operations select a bucket at random (`ThreadLocalRandom.current().nextInt(N)`), minimizing lock contention.
  2. Implement **Snapshot Checkpointing Service**:
     * Scheduled task to periodically update `account_snapshots` from the append-only `postings` log.
     * Ensure balance reads calculate: $\text{Snapshot} + \sum \text{Postings after snapshot timestamp}$.
* **Exit Criteria:**
  * Benchmark shows reduced lock wait times on hot recipient accounts by $>3\times$ compared to single-row locking.

---

### Phase 7: Benchmarking (k6) & Automated Reconciliation

* **Objective:** Validate the 5,000 TPS target and prove zero balance drift.
* **Tasks:**
  1. Author k6 benchmark scripts under `k6/`:
     * `k6/load-test-uniform.js`: 5,000 TPS across 10,000 distributed retail accounts.
     * `k6/stress-test-hotspot.js`: Heavy-contention traffic funneling to a single merchant.
  2. Build an automated post-run **Reconciliation Audit Script**:
     ```sql
     -- Invariant 1: Parity check across entire system
     SELECT SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) AS system_drift FROM postings;
     
     -- Invariant 2: Snapshot parity check
     SELECT a.id, a.cleared_balance, COALESCE(SUM(CASE WHEN p.direction = 'CREDIT' THEN p.amount ELSE -p.amount END), 0) AS derived_balance
     FROM account_snapshots a
     LEFT JOIN postings p ON a.account_id = p.account_id
     GROUP BY a.id, a.cleared_balance
     HAVING a.cleared_balance <> COALESCE(SUM(CASE WHEN p.direction = 'CREDIT' THEN p.amount ELSE -p.amount END), 0);
     ```
  3. Collect and document $p50$, $p95$, and $p99$ latency profiles under load.
* **Exit Criteria:**
  * Post-run audit reports `system_drift = 0.0000` and zero snapshot discrepancies.
  * 5,000 TPS verified under uniform load with error rate $< 0.01\%$.