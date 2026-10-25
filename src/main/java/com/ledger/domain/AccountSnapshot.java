package com.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "account_snapshots")
public class AccountSnapshot {
    private static final int BALANCE_SCALE = 4;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_id", nullable = false, unique = true)
    private UUID accountId;

    @Column(name = "cleared_balance", nullable = false, precision = 18, scale = BALANCE_SCALE)
    private BigDecimal clearedBalance;

    @Column(name = "pending_balance", nullable = false, precision = 18, scale = BALANCE_SCALE)
    private BigDecimal pendingBalance;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "last_journal_entry_id")
    private UUID lastJournalEntryId;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected AccountSnapshot() {
    }

    public AccountSnapshot(UUID accountId, BigDecimal clearedBalance, BigDecimal pendingBalance, Long version,
                           UUID lastJournalEntryId, OffsetDateTime updatedAt) {
        this.accountId = accountId;
        this.clearedBalance = normalizeBalance(clearedBalance);
        this.pendingBalance = normalizeBalance(pendingBalance);
        this.version = version;
        this.lastJournalEntryId = lastJournalEntryId;
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

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
