package com.ledger.exception;

public class JournalEntryNotFoundException extends RuntimeException {
    public JournalEntryNotFoundException(String message) {
        super(message);
    }
}
