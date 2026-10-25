package com.ledger.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public class PostTransactionRequest {
    @NotBlank
    @Size(max = 128)
    private String idempotencyKey;

    @NotNull
    private String narration;

    @NotNull
    @Size(min = 2)
    @Valid
    private List<PostingRequest> postings;

    public PostTransactionRequest() {
    }

    public PostTransactionRequest(String idempotencyKey, String narration, List<PostingRequest> postings) {
        this.idempotencyKey = idempotencyKey;
        this.narration = narration;
        this.postings = postings;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getNarration() {
        return narration;
    }

    public void setNarration(String narration) {
        this.narration = narration;
    }

    public List<PostingRequest> getPostings() {
        return postings;
    }

    public void setPostings(List<PostingRequest> postings) {
        this.postings = postings;
    }
}
