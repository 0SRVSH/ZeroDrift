package com.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Immutable;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "postings")
public class Posting {
    private static final int AMOUNT_SCALE = 4;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "journal_entry_id", nullable = false)
    private UUID journalEntryId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private PostingDirection direction;

    @Column(nullable = false, precision = 18, scale = AMOUNT_SCALE)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "sequence_order", nullable = false, columnDefinition = "smallint")
    private Integer sequenceOrder;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected Posting() {
    }

    public Posting(UUID journalEntryId, UUID accountId, PostingDirection direction, BigDecimal amount,
                   Integer sequenceOrder, OffsetDateTime createdAt) {
        this.journalEntryId = journalEntryId;
        this.accountId = accountId;
        this.direction = direction;
        this.amount = amount.setScale(AMOUNT_SCALE, RoundingMode.UNNECESSARY);
        this.sequenceOrder = sequenceOrder;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getJournalEntryId() {
        return journalEntryId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public PostingDirection getDirection() {
        return direction;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Integer getSequenceOrder() {
        return sequenceOrder;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
