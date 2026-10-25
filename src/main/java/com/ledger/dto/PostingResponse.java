package com.ledger.dto;

import com.ledger.domain.PostingDirection;

import java.math.BigDecimal;
import java.util.UUID;

public record PostingResponse(UUID accountId, PostingDirection direction, BigDecimal amount) {
}
