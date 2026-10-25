package com.ledger.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AccountBalanceResponse(UUID accountId, BigDecimal clearedBalance, BigDecimal pendingBalance,
                                     OffsetDateTime updatedAt) {
}
