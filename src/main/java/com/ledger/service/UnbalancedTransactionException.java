package com.ledger.service;

public class UnbalancedTransactionException extends RuntimeException {
    public UnbalancedTransactionException(String message) {
        super(message);
    }
}
