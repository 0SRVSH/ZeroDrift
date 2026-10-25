package com.ledger.domain;

public enum IdempotencyStatus {
    PENDING,
    COMMITTED,
    REJECTED
}
