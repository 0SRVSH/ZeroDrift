package com.ledger.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AccountBalanceResponse(UUID accountId, BigDecimal clearedBalance, BigDecimal pendingBalance,
                                     OffsetDateTime updatedAt) {
    public AccountBalanceResponse {
        clearedBalance = clearedBalance.setScale(4, RoundingMode.UNNECESSARY);
        pendingBalance = pendingBalance.setScale(4, RoundingMode.UNNECESSARY);
    }
}
