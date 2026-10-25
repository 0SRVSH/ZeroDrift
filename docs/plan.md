# plan.md

## High-Performance Double-Entry Financial Ledger: Implementation Roadmap

This implementation plan is structured for phased execution by an engineering team or AI code assistant. Each phase specifies concrete tasks, implementation requirements, architectural boundaries, and explicit exit criteria that must pass before advancing to the next phase[cite: 1].

---

### Phase 1: Environment Setup, Containerization & Database Migrations

* **Objective:** Establish the foundational infrastructure, build configuration, and database schema with engine-level parity validation[cite: 1].
* **Tasks:**
  1. Initialize a Spring Boot 3.x project using Java 25 with dependencies: `spring-boot-starter-data-jpa`, `spring-boot-starter-web`, `flyway-core`, `flyway-database-postgresql`, `postgresql`, `redisson-spring-boot-starter`, and `lombok`.
  2. Configure `docker-compose.yml` defining:
     * **PostgreSQL 16** with a dedicated healthcheck and persistent volume.
     * **Redis 7 (Alpine)** for caching and distributed locks.
  3. Author Flyway migration scripts under `src/main/resources/db/migration/`:
     * `V1__init_core_ledger_schema.sql`: Tables for `idempotency_records`, `accounts`, `account_snapshots`, `journal_entries`, and `postings`[cite: 1].
     * `V2__add_debit_credit_parity_trigger.sql`: Plpgsql trigger `trg_postings_verify_parity` (marked `DEFERRABLE INITIALLY DEFERRED`) asserting net debit-credit parity per transaction[cite: 1].
     * `V3__add_performance_indexes.sql`: Composite and partial indexes for queries, range audits, and idempotency checks[cite: 1].
  4. Configure `application.yml` with connection pooling (HikariCP) and Flyway auto-migrate flags enabled.
* **Exit Criteria:**
  * `docker compose up -d` boots both services successfully.
  * Flyway executes cleanly on application boot.
  * Running the SQL assertions in `steps.md` confirms that balanced entries commit and unbalanced entries fail with a parity exception[cite: 1].

---

### Phase 2: Domain Entities, Repositories & Data Access Layer

* **Objective:** Map relational models into Java domain classes without introducing floating-point arithmetic or ORM lock bypasses.
* **Tasks:**
  1. Implement immutable domain records / entities:
     * `Account` (UUID id, accountNumber, currency, accountType, isActive)
     * `AccountSnapshot` (UUID id and accountId, cleared and pending balances as `BigDecimal`, version, lastJournalEntryId, updatedAt)
     * `JournalEntry` (id, idempotencyKey, narration, status [`COMMITTED`/`REJECTED`], postedAt), immutable and append-only
     * `Posting` (id, journalEntryId, accountId, direction [`DEBIT`/`CREDIT`], amount as `BigDecimal`, sequenceOrder), immutable and append-only[cite: 1]
     * `IdempotencyRecord` (idempotencyKey, status [`PENDING`/`COMMITTED`/`REJECTED`], requestHash, JSON responsePayload, createdAt, expiresAt)[cite: 1]
  2. Create Spring Data repositories for all five entities. `AccountSnapshotRepository` must provide an ordered pessimistic write-lock query by account ID.
  3. Use explicit scale-4 `BigDecimal` mappings for monetary fields; normalize constructor values with `RoundingMode.UNNECESSARY`.
* **Exit Criteria:**
  * Unit tests confirm `AccountSnapshot` amounts cannot be instantiated using `Double` or `Float`.
  * Repository tests verify that `lockAccountsForUpdate` returns accounts in strictly sorted ascending order.

---

### Phase 3: Core Transfer Engine & Deterministic Concurrency Control

* **Objective:** Implement the core double-entry transfer pipeline with guaranteed zero negative balances and deadlock prevention[cite: 1].
* **Tasks:**
  1. Create `TransferService` with transactional execution (`@Transactional(isolation = Isolation.READ_COMMITTED)`).
  2. Implement transfer validation pipeline:
     * Sort input account UUIDs ascending (`sourceId`, `destinationId`)[cite: 1].
     * Acquire pessimistic row locks across snapshots in that sorted sequence[cite: 1].
     * Validate currency match and check that `clearedBalance >= transferAmount`.
     * Throw `InsufficientFundsException` on balance shortfall.
  3. Implement ledger persistence:
     * Insert `journal_entries` record[cite: 1].
     * Insert two `postings` records: Leg 1 (Source, DEBIT, amount) and Leg 2 (Destination, CREDIT, amount)[cite: 1].
     * Update source snapshot (`clearedBalance - amount`) and destination snapshot (`clearedBalance + amount`).
* **Exit Criteria:**
  * Multi-threaded integration test firing 100 concurrent transfers in opposite directions ($A \to B$ and $B \to A$) completes with **zero deadlocks** and exact balance conservation.

---

### Phase 4: Distributed Idempotency Pipeline & Web API Layer

* **Objective:** Expose a resilient REST API protected by Redis distributed locks and a database-backed idempotency state machine[cite: 1].
* **Tasks:**
  1. Define API request/response DTOs:
     * `TransferRequest` (sourceAccountId, destinationAccountId, amount, currency, narration).
     * `TransferResponse` (transactionId, status, postedAt, debitedAmount, creditedAmount).
  2. Implement `IdempotencyService` and an outer orchestrator (non-transactional):
     * Enforce presence of `Idempotency-Key` header[cite: 1].
     * Acquire a short-lived distributed lock via Redisson: `lock:idempotency:<key>`.
     * Check database `idempotency_records`:
       * If `COMMITTED`: return cached response body directly (HTTP 200)[cite: 1].
       * If `PENDING`: reject concurrent duplicate request with HTTP `409 Conflict`[cite: 1].
       * If non-existent: insert row with status `PENDING` and request payload hash[cite: 1].
     * Delegate to `@Transactional` `TransferService`.
     * Upon commit, update idempotency record to `COMMITTED` with the serialized JSON response payload[cite: 1].
     * On unrecoverable validation failure, mark as `REJECTED`[cite: 1].
     * Release Redisson distributed lock in a `finally` block.
  3. Implement `TransferController` mapping `POST /api/v1/transfers`.
* **Exit Criteria:**
  * Firing 20 concurrent requests with the identical `Idempotency-Key` results in exactly 1 database write and 19 cached responses or conflict rejections, with zero duplicate debits[cite: 1].

---

### Phase 5: High-Concurrency Integration Testing (Testcontainers)

* **Objective:** Validate system invariants under multithreaded contention without mocking storage layers[cite: 1].
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

* **Objective:** Mitigate single-row lock serialization on high-volume recipient accounts[cite: 1].
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

* **Objective:** Validate the 5,000 TPS target and prove zero balance drift[cite: 1].
* **Tasks:**
  1. Author k6 benchmark scripts under `k6/`:
     * `k6/load-test-uniform.js`: 5,000 TPS across 10,000 distributed retail accounts[cite: 1].
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
  * Post-run audit reports `system_drift = 0.0000` and zero snapshot discrepancies[cite: 1].
  * 5,000 TPS verified under uniform load with error rate $< 0.01\%$[cite: 1].