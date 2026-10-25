package com.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "account_snapshots", uniqueConstraints = @UniqueConstraint(
        name = "uq_account_snapshots_account_bucket", columnNames = {"account_id", "bucket_index"}))
public class AccountSnapshot {
    private static final int BALANCE_SCALE = 4;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "bucket_index", nullable = false)
    private int bucketIndex;

    @Column(name = "cleared_balance", nullable = false, precision = 18, scale = BALANCE_SCALE)
    private BigDecimal clearedBalance;

    @Column(name = "pending_balance", nullable = false, precision = 18, scale = BALANCE_SCALE)
    private BigDecimal pendingBalance;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "last_journal_entry_id")
    private UUID lastJournalEntryId;

    @Column(name = "last_posting_sequence", nullable = false)
    private Long lastPostingSequence;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected AccountSnapshot() {
    }

    public AccountSnapshot(UUID accountId, BigDecimal clearedBalance, BigDecimal pendingBalance, Long version,
                           UUID lastJournalEntryId, OffsetDateTime updatedAt) {
        this(accountId, 0, clearedBalance, pendingBalance, version, lastJournalEntryId, 0L, updatedAt);
    }

    public AccountSnapshot(UUID accountId, int bucketIndex, BigDecimal clearedBalance, BigDecimal pendingBalance,
                           Long version, UUID lastJournalEntryId, Long lastPostingSequence,
                           OffsetDateTime updatedAt) {
        if (bucketIndex < 0) {
            throw new IllegalArgumentException("Bucket index must not be negative");
        }
        this.accountId = accountId;
        this.bucketIndex = bucketIndex;
        this.clearedBalance = normalizeBalance(clearedBalance);
        this.pendingBalance = normalizeBalance(pendingBalance);
        this.version = version;
        this.lastJournalEntryId = lastJournalEntryId;
        this.lastPostingSequence = lastPostingSequence;
        this.updatedAt = updatedAt;
    }

    private static BigDecimal normalizeBalance(BigDecimal balance) {
        return balance.setScale(BALANCE_SCALE, RoundingMode.UNNECESSARY);
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public int getBucketIndex() {
        return bucketIndex;
    }

    public BigDecimal getClearedBalance() {
        return clearedBalance;
    }

    public BigDecimal getPendingBalance() {
        return pendingBalance;
    }

    public Long getVersion() {
        return version;
    }

    public UUID getLastJournalEntryId() {
        return lastJournalEntryId;
    }

    public Long getLastPostingSequence() {
        return lastPostingSequence;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void applyClearedBalance(BigDecimal balance, UUID journalEntryId, long postingSequence,
                                    OffsetDateTime updatedAt) {
        this.clearedBalance = normalizeBalance(balance);
        this.lastJournalEntryId = journalEntryId;
        this.lastPostingSequence = postingSequence;
        this.updatedAt = updatedAt;
    }

    public void applyCheckpoint(BigDecimal balanceDelta, UUID journalEntryId, long postingSequence,
                                OffsetDateTime updatedAt) {
        this.clearedBalance = normalizeBalance(clearedBalance.add(balanceDelta));
        this.lastJournalEntryId = journalEntryId;
        this.lastPostingSequence = postingSequence;
        this.updatedAt = updatedAt;
    }
}
