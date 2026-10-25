package com.ledger.dto;

import com.ledger.domain.JournalEntryStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record TransactionResponse(UUID journalEntryId, String idempotencyKey, JournalEntryStatus status,
                                  OffsetDateTime postedAt, List<PostingResponse> postings) {
    public TransactionResponse {
        postings = List.copyOf(postings);
    }
}
