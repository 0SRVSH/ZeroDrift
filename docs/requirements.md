# requirements.md

This document serves as the master specification, architectural constraint definition, and AI prompt guardrail for the High-Performance Double-Entry Financial Ledger project.
Any AI code assistant (e.g., Cursor, Claude Code, GitHub Copilot) reading this repository **must strictly obey the directives, architectural boundaries, and warnings detailed herein**.

---

## 1. System Invariants & Functional Requirements

### 1.1 Invariant 1: Absolute Debit-Credit Parity (Zero Drift)
* Every financial transaction is represented by a single `journal_entry` comprising at least two `postings`.
* For every transaction, the algebraic sum of all debits and credits must evaluate to zero:
  $$\sum \text{Debits} - \sum \text{Credits} \equiv 0.0000$$
* The application validates transaction parity, and the deferred PostgreSQL constraint trigger checks parity at commit for journal entries touched by posting inserts or updates. Posting deletes do not fire this trigger.

### 1.2 Invariant 2: Append-Only Immutability
* Account master rows have no balance column. The application updates materialized account snapshots as postings are committed.
* The application treats journal entries and postings as append-only and does not update or delete them.
* The PostgreSQL parity trigger validates posting inserts and updates, but does not itself prohibit updates or deletes. Database-level append-only enforcement is not currently installed.
* Corrections, adjustments, or cancellations should be issued as new, discrete offsetting journal entries.

### 1.3 Invariant 3: Idempotent Request Execution
* `POST /api/v1/ledger/transactions` requires `idempotencyKey` in the JSON request body; the controller does not read an `Idempotency-Key` header.
* The schema supports `PENDING`, `COMMITTED`, and `REJECTED` idempotency statuses. The successful request path writes `PENDING`, invokes the ledger transaction, and then writes `COMMITTED`; failed attempts are not currently transitioned to `REJECTED`, so a failure after saving `PENDING` can leave retries returning HTTP `409`.
* A retry with an identical key and request hash returns the cached committed response without re-executing ledger writes. Reuse with a different request hash, or a key currently in progress, returns HTTP `409 Conflict`.
* The key must be nonblank and no longer than 128 characters.
* `expires_at` is populated, but no cleanup job currently expires records or releases keys.

### 1.4 Invariant 4: Deterministic Concurrency Control
* High-concurrency operations across identical accounts must not result in cyclic deadlocks or negative-balance race conditions.
* The engine sorts distinct account UUIDs in ascending order and acquires each required snapshot lock in ascending bucket-index order.
* Writes and balance reads use materialized account snapshots rather than scanning the full posting history. The scheduled checkpoint applies posting deltas after each snapshot's last posting sequence.
* The local uniform benchmark profile is 100 TPS by default (configurable to 200 TPS) with 20 preallocated and at most 50 VUs. A distributed 5,000 TPS profile is a target and must not be represented as an achieved result without benchmark evidence.

---

## 2. Mandatory AI Assistant Guardrails & Technical Warnings

> **DIRECTIVE FOR AI CODE GENERATORS:**  
> Financial ledger systems fail in subtle, catastrophic ways when standard CRUD conventions are applied. Do NOT default to typical web application patterns. Adhere to the following rules without exception.

### ⚠️ Warning 1: Floating-Point Arithmetic is Strictly Prohibited
* **Violation:** Using `float`, `double`, or primitive wrapper classes (`Float`, `Double`) for monetary calculations.
* **Failure Mode:** IEEE-754 floating-point representation errors cause fractional-cent discrepancies (e.g., `0.1 + 0.2 = 0.30000000000000004`), violating database parity triggers.
* **Mandatory Rule:** Always use `java.math.BigDecimal` with explicit scale (`4` decimal places) and explicit rounding modes (`RoundingMode.UNNECESSARY` or `RoundingMode.HALF_EVEN`). In SQL, always use `NUMERIC(18, 4)`.

### ⚠️ Warning 2: Append-Only Enforcement Boundary
* **Current Enforcement:** The application creates journal entries and postings but does not update or delete them. The deferred parity trigger validates inserts and updates; it does not reject deletes or make these tables immutable at the database level.
* **Rule:** Model corrections as new offsetting entries. Add database-level mutation guards if immutability must be enforced independently of application behavior.

### ⚠️ Warning 3: The Distributed Lock vs. `@Transactional` Boundary Flaw
* **Violation:** Placing a distributed lock (e.g., Redisson) inside a method annotated with Spring's `@Transactional`, or using a standard Spring AOP aspect that releases the lock upon method return.
* **Failure Mode:** In Spring, `@Transactional` commits the database transaction *after* the method body finishes executing. If the distributed lock is released before the transaction is physically committed to PostgreSQL, a competing thread can acquire the lock, read stale uncommitted data, and introduce balance corruption.
* **Mandatory Rule:** Distributed locks must enclose the transaction boundary externally:
  ```text
  1. Acquire Redis Distributed Lock
     └── 2. Open Spring DB Transaction (@Transactional Isolation = READ_COMMITTED)
            └── 3. Execute Deterministic Row Locks & Transfer Logic
     └── 4. Commit DB Transaction to Disk
  5. Release Redis Distributed Lock
  ```

### ⚠️ Warning 4: Row-Level Locking Deadlocks
* **Violation:** Querying `SELECT ... FOR UPDATE` using the account order provided by the client request payload (e.g., locking `source_account` then locking `destination_account`).
* **Failure Mode:** If User A transfers to User B while User B transfers to User A simultaneously:
  * Thread 1 locks A, attempts to lock B.
  * Thread 2 locks B, attempts to lock A.
  * Result: PostgreSQL detects a cyclic dependency and throws `PSQLException: ERROR: deadlock detected`.
* **Mandatory Rule:** Collect distinct account UUIDs, sort them ascending, then acquire each affected account's required snapshot bucket locks in ascending bucket-index order.

### ⚠️ Warning 5: Snapshot and Checkpoint Behavior
* **Current Behavior:** The write engine reads and updates locked snapshot rows in the posting transaction. A scheduled checkpoint applies postings newer than `last_posting_sequence`; the balance endpoint sums snapshot rows and does not query postings.

### ⚠️ Warning 6: Virtual Thread Pinning
* **Violation:** Performing long-running or blocking native/foreign-function operations on virtual threads in the transfer pipeline.
* **Failure Mode:** Native and foreign-function calls can pin virtual threads to their underlying OS carrier threads, reducing the concurrency benefits of Project Loom and degrading throughput. Starting with Java 24, blocking while holding a `synchronized` monitor no longer causes this form of pinning.
* **Mandatory Rule:** Keep native/foreign-function calls brief on virtual threads. Use `java.util.concurrent.locks.ReentrantLock` when explicit in-memory lock control is required; do not rely on it solely to avoid monitor pinning on Java 25.

---

## 3. Protocol for AI Code Generation (Human-in-the-Loop)

To prevent code drift and unverified implementations, any AI interacting with this repository must follow this operational flow:

1. **No Silent Scaffolding:** Do NOT generate multiple complex service classes or database schema changes at once without first explaining the concurrency implications.
2. **Explain First, Implement Second:** Before generating code for any phase:
   * Explain which lock boundaries are being introduced.
   * State clearly how the debit-credit parity invariant is preserved.
   * Identify potential failure modes (e.g., deadlocks, timeout rollbacks, network partition risks).
3. **Wait for Developer Confirmation:** When dealing with core transaction boundaries, idempotency transitions, and database triggers, summarize the design and wait for human review before outputting production code.

---

## 4. Edge Cases & Concurrency Failure Modes Matrix

| Failure Scenario | Risk | Mitigation Strategy |
| :--- | :--- | :--- |
| **Simultaneous Bidirectional Transfers ($A \to B$ & $B \to A$)** | Relational database cyclic deadlock. | Sort account UUIDs ascending, then lock each affected account's required buckets in ascending index order. |
| **Network Timeout Post-DB Commit** | Client receives timeout error, retries transfer, risking double-debiting. | Client sends `idempotencyKey` in the JSON body. Service detects an existing committed request with the same hash and returns its cached response. |
| **Concurrent Duplicate API Submissions** | Two identical requests hit different backend instances at the exact same millisecond. | Redisson lock `lock:txn:<key>` serializes request intake; the JSON-body key is used. A request that cannot acquire the lock receives HTTP 409. |
| **Hot-Spot Merchant Account** | Thousands of buyers credit a single merchant account concurrently, serializing on one row lock. | Decouple authorization holds from clearing, or implement balance sharding (splitting account snapshot into $N$ buckets). |
| **Unbalanced Transaction Injection** | Application bug inserts mismatched debit and credit legs. | PostgreSQL deferred constraint trigger `trg_postings_verify_parity` validates parity after posting inserts or updates and rejects an unbalanced commit. |