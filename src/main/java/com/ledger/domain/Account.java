package com.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_number", nullable = false, unique = true, length = 64)
    private String accountNumber;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType accountType;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "bucket_count", nullable = false)
    private int bucketCount = 1;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected Account() {
    }

    public Account(String accountNumber, String currency, AccountType accountType, boolean active,
                   OffsetDateTime createdAt) {
        this(accountNumber, currency, accountType, active, createdAt, 1);
    }

    public Account(String accountNumber, String currency, AccountType accountType, boolean active,
                   OffsetDateTime createdAt, int bucketCount) {
        if (bucketCount < 1) {
            throw new IllegalArgumentException("Bucket count must be at least one");
        }
        this.accountNumber = accountNumber;
        this.currency = currency;
        this.accountType = accountType;
        this.active = active;
        this.createdAt = createdAt;
        this.bucketCount = bucketCount;
    }

    public UUID getId() {
        return id;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getCurrency() {
        return currency;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public boolean isActive() {
        return active;
    }

    public int getBucketCount() {
        return bucketCount;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
