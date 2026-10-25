package com.ledger.dto;

import com.ledger.domain.PostingDirection;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

public class PostingRequest {
    @NotNull
    private UUID accountId;

    @NotNull
    private PostingDirection direction;

    @NotNull
    @Positive
    @Digits(integer = 14, fraction = 4)
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
