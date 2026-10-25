package com.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {
    @Id
    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdempotencyStatus status;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_payload", columnDefinition = "jsonb")
    private String responsePayload;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(String idempotencyKey, IdempotencyStatus status, String requestHash,
                             String responsePayload, OffsetDateTime createdAt, OffsetDateTime expiresAt) {
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.requestHash = requestHash;
        this.responsePayload = responsePayload;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getResponsePayload() {
        return responsePayload;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void markCommitted(String responsePayload) {
        if (status != IdempotencyStatus.PENDING) {
            throw new IllegalStateException("Only pending idempotency records can be committed");
        }
        this.status = IdempotencyStatus.COMMITTED;
        this.responsePayload = responsePayload;
    }
}
