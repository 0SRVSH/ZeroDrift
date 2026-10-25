package com.ledger.dto;

import com.ledger.domain.PostingDirection;

import java.math.BigDecimal;
import java.util.UUID;

public class PostingRequest {
    private UUID accountId;
    private PostingDirection direction;
    private BigDecimal amount;

    public PostingRequest() {
    }

    public PostingRequest(UUID accountId, PostingDirection direction, BigDecimal amount) {
        this.accountId = accountId;
        this.direction = direction;
        this.amount = amount;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public void setAccountId(UUID accountId) {
        this.accountId = accountId;
    }

    public PostingDirection getDirection() {
        return direction;
    }

    public void setDirection(PostingDirection direction) {
        this.direction = direction;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
