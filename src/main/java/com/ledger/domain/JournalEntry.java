package com.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "journal_entries")
public class JournalEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
    private String idempotencyKey;

    @Column(nullable = false, columnDefinition = "text")
    private String narration;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JournalEntryStatus status;

    @Column(name = "posted_at", nullable = false)
    private OffsetDateTime postedAt;

    protected JournalEntry() {
    }

    public JournalEntry(String idempotencyKey, String narration, JournalEntryStatus status,
                        OffsetDateTime postedAt) {
        this.idempotencyKey = idempotencyKey;
        this.narration = narration;
        this.status = status;
        this.postedAt = postedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getNarration() {
        return narration;
    }

    public JournalEntryStatus getStatus() {
        return status;
    }

    public OffsetDateTime getPostedAt() {
        return postedAt;
    }
}
