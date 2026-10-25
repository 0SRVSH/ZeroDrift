# requirements.md

This document serves as the master specification, architectural constraint definition, and AI prompt guardrail for the High-Performance Double-Entry Financial Ledger project[cite: 1]. 

Any AI code assistant (e.g., Cursor, Claude Code, GitHub Copilot) reading this repository **must strictly obey the directives, architectural boundaries, and warnings detailed herein**.

---

## 1. System Invariants & Functional Requirements

### 1.1 Invariant 1: Absolute Debit-Credit Parity (Zero Drift)
* Every financial transaction is represented by a single `journal_entry` comprising at least two `postings`[cite: 1].
* For every transaction, the algebraic sum of all debits and credits must evaluate to zero[cite: 1]:
  $$\sum \text{Debits} - \sum \text{Credits} \equiv 0.0000$$
* Parity is enforced not merely at the application layer, but directly at the database storage engine via deferred constraint triggers[cite: 1]. Any transaction violating this rule must fail at commit time[cite: 1].

### 1.2 Invariant 2: Append-Only Immutability
* Balances are never modified directly with destructive updates (`UPDATE accounts SET balance = balance - X` is forbidden)[cite: 1].
* Ledger postings and journal entries are strictly append-only[cite: 1].
* No `UPDATE` or `DELETE` SQL queries shall be issued or configured for the `postings` or `journal_entries` tables[cite: 1].
* Corrections, adjustments, or cancellations must be issued as new, discrete offsetting journal entries.

### 1.3 Invariant 3: Exact-Once Execution & Idempotency
* Every state-changing API request must provide an `Idempotency-Key` header[cite: 1].
* Requests are processed through a three-phase state machine: `PENDING`, `COMMITTED`, or `REJECTED`[cite: 1].
* Retried requests with an identical idempotency key must return the original cached response payload without re-executing transfers[cite: 1].
* In-flight requests bearing an active `PENDING` key must be rejected with HTTP `409 Conflict` (or HTTP `425 Too Early`) to prevent concurrent race collisions[cite: 1].

### 1.4 Invariant 4: Deterministic Concurrency Control
* High-concurrency operations across identical accounts must not result in cyclic deadlocks or negative-balance race conditions[cite: 1].
* All pessimistic database row locks (`SELECT ... FOR UPDATE`) must be acquired in a globally deterministic order (e.g., sorted lexicographically by account UUID)[cite: 1].
* To support high throughput (targeting 5,000 TPS under benchmark load), balances are anchored by materialized account snapshots rather than scanning unbounded historical tables[cite: 1].

---

## 2. Mandatory AI Assistant Guardrails & Technical Warnings

> **DIRECTIVE FOR AI CODE GENERATORS:**  
> Financial ledger systems fail in subtle, catastrophic ways when standard CRUD conventions are applied. Do NOT default to typical web application patterns. Adhere to the following rules without exception.

### ⚠️ Warning 1: Floating-Point Arithmetic is Strictly Prohibited
* **Violation:** Using `float`, `double`, or primitive wrapper classes (`Float`, `Double`) for monetary calculations.
* **Failure Mode:** IEEE-754 floating-point representation errors cause fractional-cent discrepancies (e.g., `0.1 + 0.2 = 0.30000000000000004`), violating database parity triggers.
* **Mandatory Rule:** Always use `java.math.BigDecimal` with explicit scale (`4` decimal places) and explicit rounding modes (`RoundingMode.UNNECESSARY` or `RoundingMode.HALF_EVEN`). In SQL, always use `NUMERIC(18, 4)`.

### ⚠️ Warning 2: The Distributed Lock vs. `@Transactional` Boundary Flaw
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

### ⚠️ Warning 3: Row-Level Locking Deadlocks
* **Violation:** Querying `SELECT ... FOR UPDATE` using the account order provided by the client request payload (e.g., locking `source_account` then locking `destination_account`)[cite: 1].
* **Failure Mode:** If User A transfers to User B while User B transfers to User A simultaneously:
  * Thread 1 locks A, attempts to lock B.
  * Thread 2 locks B, attempts to lock A.
  * Result: PostgreSQL detects a cyclic dependency and throws `PSQLException: ERROR: deadlock detected`.
* **Mandatory Rule:** Collect all affected account UUIDs, sort them ascending (`ids.stream().sorted().toList()`), and execute row-level locks strictly in that deterministic sequence[cite: 1].

### ⚠️ Warning 4: Derived Balance Table-Scan Bottlenecks
* **Violation:** Calculating an account's available balance by executing `SELECT SUM(...) FROM postings WHERE account_id = ?` under a write lock during high TPS transfers[cite: 1].
* **Failure Mode:** As the `postings` table grows into millions of rows, write latency degrades linearly ($O(N)$), causing lock hold times to skyrocket and thread pools to exhaust.
* **Mandatory Rule:** Acquire the row lock on `account_snapshots`[cite: 1]. Derive real-time balance using the materialized snapshot balance combined only with postings created *after* the snapshot's recorded sequence checkpoint.

### ⚠️ Warning 5: Virtual Thread Pinning
* **Violation:** Performing long-running or blocking native/foreign-function operations on virtual threads in the transfer pipeline.
* **Failure Mode:** Native and foreign-function calls can pin virtual threads to their underlying OS carrier threads, reducing the concurrency benefits of Project Loom and degrading throughput. Starting with Java 24, blocking while holding a `synchronized` monitor no longer causes this form of pinning.
* **Mandatory Rule:** Keep native/foreign-function calls brief on virtual threads. Use `java.util.concurrent.locks.ReentrantLock` when explicit in-memory lock control is required; do not rely on it solely to avoid monitor pinning on Java 25.

---

## 3. Protocol for AI Code Generation (Human-in-the-Loop)

To prevent code drift and unverified implementations, any AI interacting with this repository must follow this operational flow:

1. **No Silent Scaffolding:** Do NOT generate multiple complex service classes or database schema changes at once without first explaining the concurrency implications.
2. **Explain First, Implement Second:** Before generating code for any phase:
   * Explain which lock boundaries are being introduced.
   * State clearly how the debit-credit parity invariant is preserved[cite: 1].
   * Identify potential failure modes (e.g., deadlocks, timeout rollbacks, network partition risks).
3. **Wait for Developer Confirmation:** When dealing with core transaction boundaries, idempotency transitions, and database triggers, summarize the design and wait for human review before outputting production code[cite: 1].

---

## 4. Edge Cases & Concurrency Failure Modes Matrix

| Failure Scenario | Risk | Mitigation Strategy |
| :--- | :--- | :--- |
| **Simultaneous Bidirectional Transfers ($A \to B$ & $B \to A$)** | Relational database cyclic deadlock. | Sort account IDs lexicographically prior to issuing `SELECT ... FOR UPDATE`[cite: 1]. |
| **Network Timeout Post-DB Commit** | Client receives timeout error, retries transfer, risking double-debiting[cite: 1]. | Client supplies unique `Idempotency-Key`[cite: 1]. Service detects existing `COMMITTED` status and returns cached result payload[cite: 1]. |
| **Concurrent Duplicate API Submissions** | Two identical requests hit different backend instances at the exact same millisecond. | Redis distributed lock on `idempotency:<key>` serializes request intake; first thread inserts `PENDING` in PostgreSQL; second thread is rejected with HTTP 409[cite: 1]. |
| **Hot-Spot Merchant Account** | Thousands of buyers credit a single merchant account concurrently, serializing on one row lock. | Decouple authorization holds from clearing, or implement balance sharding (splitting account snapshot into $N$ buckets). |
| **Unbalanced Transaction Injection** | Application bug inserts mismatched debit and credit legs. | PostgreSQL deferred constraint trigger `trg_postings_verify_parity` rejects the commit at storage level[cite: 1]. |