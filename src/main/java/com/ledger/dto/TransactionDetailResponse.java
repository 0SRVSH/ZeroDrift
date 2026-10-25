package com.ledger.dto;

import com.ledger.domain.JournalEntry;
import com.ledger.domain.Posting;

import java.util.List;

public record TransactionDetailResponse(JournalEntry journalEntry, List<Posting> postings) {
    public TransactionDetailResponse {
        postings = List.copyOf(postings);
    }
}
