package com.ledger.service;

import com.ledger.domain.PostingDirection;
import com.ledger.dto.PostTransactionRequest;
import com.ledger.dto.PostingRequest;
import com.ledger.exception.UnbalancedTransactionException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

@Service
public class LedgerValidationService {
    private static final int AMOUNT_SCALE = 4;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(AMOUNT_SCALE);

    public void validate(PostTransactionRequest request) {
        Objects.requireNonNull(request, "Transaction request must not be null");
        if (request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank()
                || request.getIdempotencyKey().length() > 128) {
            throw new IllegalArgumentException("Idempotency key must contain between 1 and 128 characters");
        }
        if (request.getNarration() == null) {
            throw new IllegalArgumentException("Narration must not be null");
        }

        List<PostingRequest> postings = request.getPostings();
        if (postings == null || postings.size() < 2) {
            throw new IllegalArgumentException("A transaction must contain at least two postings");
        }

        BigDecimal debits = ZERO;
        BigDecimal credits = ZERO;
        for (PostingRequest posting : postings) {
            if (posting == null || posting.getAccountId() == null || posting.getDirection() == null) {
                throw new IllegalArgumentException("Each posting must have an account and direction");
            }
            BigDecimal amount = normalizeAmount(posting.getAmount());
            if (posting.getDirection() == PostingDirection.DEBIT) {
                debits = debits.add(amount);
            } else {
                credits = credits.add(amount);
            }
        }
        if (debits.subtract(credits).compareTo(ZERO) != 0) {
            throw new UnbalancedTransactionException("Debits and credits must balance to scale 4");
        }
    }

    public BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Posting amount must be greater than zero");
        }
        if (amount.stripTrailingZeros().scale() > AMOUNT_SCALE) {
            throw new IllegalArgumentException("Posting amount must not have more than four decimal places");
        }
        BigDecimal normalized;
        try {
            normalized = amount.setScale(AMOUNT_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Posting amount must be exactly representable at scale 4", exception);
        }
        if (normalized.precision() > 18) {
            throw new IllegalArgumentException("Posting amount exceeds the supported precision");
        }
        return normalized;
    }
}
