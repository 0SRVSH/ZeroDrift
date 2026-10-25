# ZeroDrift: High-Performance Double-Entry Financial Ledger

A mission-critical, event-sourced financial ledger built with **Java 21 (Virtual Threads), Spring Boot 3, PostgreSQL 16, and Redis 7**. 

Standard databases often drop balances or suffer severe race conditions when concurrent requests debit and credit identical accounts simultaneously[cite: 1]. This project provides a robust, zero-drift alternative: an immutable double-entry bookkeeping system where accounts maintain no direct mutable balance; balances are derived strictly from an immutable stream of balanced debits and credits[cite: 1].

---

## 1. Motivation & Industry Context

Modern payment rails and fintech clearing networks (such as Visa, Mastercard, Stripe, and Razorpay) operate on two immutable laws:
1. **Zero Data Loss & Absolute Parity:** Money cannot be created or destroyed out of thin air. For every transfer, total debits must strictly equal total credits[cite: 1].
2. **Exact-Once Processing Under Network Failure:** Unreliable networks guarantee that clients will retry transactions. Double charges and negative-balance race conditions are catastrophic failure modes that cannot be allowed[cite: 1].

While many web backends rely on naïve `UPDATE accounts SET balance = balance - X` mutations, this engine treats balance mutation as an anti-pattern[cite: 1]. Instead, it uses an event-driven ledger model with database-enforced integrity checks, deterministic pessimistic concurrency control, and distributed idempotency state machines to sustain high throughput (5,000 TPS) without data corruption[cite: 1].

---

## 2. Core Architecture & Technical Highlights

* **Immutable Double-Entry Bookkeeping:** All monetary events are append-only journal entries comprising discrete debit and credit posting legs[cite: 1]. Direct updates and deletions on ledger tables are prohibited[cite: 1].
* **Storage-Engine Parity Enforcement:** A deferred PostgreSQL trigger (`trg_postings_verify_parity`) validates that $\sum \text{Debits} - \sum \text{Credits} \equiv 0$ prior to transaction commit[cite: 1]. Unbalanced writes are rejected directly at the database engine level[cite: 1].
* **Deterministic Concurrency Control:** Prevents cyclic deadlocks and negative balances during concurrent multi-party transfers by locking accounts in lexicographical order via `SELECT ... FOR UPDATE`[cite: 1].
* **Materialized Snapshot Balances:** Solves the derived balance table-scan bottleneck by combining point-in-time balance snapshots with bounded posting deltas, avoiding full table scans during high-throughput workloads[cite: 1].
* **Distributed Idempotency Pipeline:** Exposes an idempotent transfer API using client-provided idempotency keys mapped to an atomic write status machine (`PENDING`, `COMMITTED`, `REJECTED`) combined with Redis distributed locks to eliminate replay attacks and duplicate debits[cite: 1].

---

## 3. Repository Structure

```text
financial-ledger/
├── .github/
│   └── workflows/
│       └── ci.yml                      # CI pipeline (build, test, lint)
├── docker-compose.yml                  # PostgreSQL 16 & Redis 7 services
├── k6/                                 # Load and stress benchmark scripts
│   ├── load-test-uniform.js            # 5,000 TPS uniform load test[cite: 1]
│   └── stress-test-hotspot.js          # Hot-spot merchant account stress test
├── src/
│   ├── main/
│   │   ├── java/com/ledger/
│   │   │   ├── LedgerApplication.java
│   │   │   ├── api/                    # REST Controllers, DTOs, Idempotency Filter
│   │   │   ├── config/                 # Redis/Redisson, Virtual Threads, Flyway Config
│   │   │   ├── domain/                 # Entities (JournalEntry, Posting, Account)
│   │   │   ├── exception/              # InsufficientFundsException, ConcurrencyException
│   │   │   ├── repository/             # Spring Data JPA & JdbcClient Repositories
│   │   │   └── service/                # TransferEngine, LockOrchestrator, SnapshotService
│   │   └── resources/
│   │       ├── application.yml         # Runtime configuration
│   │       └── db/migration/           # Flyway SQL migration scripts (V1, V2, V3)
│   └── test/
│       └── java/com/ledger/            # Testcontainers integration & concurrency tests
├── steps.md                            # Step-by-step database setup & Flyway guide
├── requirements.md                     # Engineering invariants & AI guardrails[cite: 1]
├── plan.md                             # Phased implementation roadmap
└── pom.xml                             # Maven build configuration
```

---

## 4. Architectural Assumptions & Domain Design

* **Currency Precision:** Currency values are never represented using floating-point types (`float`, `double`). All monetary amounts use Java `BigDecimal` (fixed scale of 4 decimal places) and PostgreSQL `NUMERIC(18, 4)` to eliminate IEEE-754 precision drift.
* **Lock Scoping:** Redis distributed locks strictly wrap the outer lifecycle of the database transaction and are released only *after* PostgreSQL acknowledges disk commit, preventing dirty reads across application nodes.
* **Deterministic Ordering:** Any operation affecting multiple accounts sorts target account identifiers ascending (`ORDER BY account_id ASC`) before acquiring pessimistic locks, eliminating database deadlocks[cite: 1].
* **Auditing Over Deletion:** Voided or adjusted transactions are never deleted from history[cite: 1]. Reversals are modeled as explicit offsetting posting legs referencing the original journal entry.

---

## 5. Getting Started

### Prerequisites
* Java 21 SDK (with Virtual Thread support enabled)
* Maven 3.9+
* Docker & Docker Compose

### 1. Start Infrastructure Dependencies
Spin up local PostgreSQL and Redis containers:
```bash
docker compose up -d
```

### 2. Run Database Migrations
Execute Flyway migrations to establish tables, indexes, and parity triggers:
```bash
./mvnw flyway:migrate
```

### 3. Build & Run Application
```bash
./mvnw clean spring-boot:run
```
The server will start on port `8080`.

### 4. Execute Multi-Threaded Concurrency Tests
Run the automated test suite powered by Testcontainers to verify deadlock-free concurrent transfers:
```bash
./mvnw test
```

---

## 6. Performance Benchmarking (k6)

The project includes pre-configured k6 benchmarking suites designed to validate transaction latency and parity under heavy load:

```bash
# Execute 5,000 TPS uniform distribution test[cite: 1]
k6 run k6/load-test-uniform.js
```

### Validated Target Metrics:
* **Throughput:** Sustained 5,000 TPS[cite: 1].
* **Debit-Credit Parity Invariant:** 100% parity verified via post-run automated audit script[cite: 1].
* **Zero Race Conditions:** Completely eliminated negative balances and duplicate transfers across concurrent runs[cite: 1].

---

## 7. Key References

* **Martin Fowler:** *Accounting Patterns & Event Sourcing*
* **TigerBeetle Design Philosophy:** *Safety and Financial Invariants in High-Throughput Storage Engines*
* **Stripe Engineering Blog:** *Designing Robust and Idempotent APIs for Payment Systems*