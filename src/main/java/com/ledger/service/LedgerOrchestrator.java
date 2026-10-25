package com.ledger.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.domain.IdempotencyRecord;
import com.ledger.domain.IdempotencyStatus;
import com.ledger.dto.PostTransactionRequest;
import com.ledger.dto.PostingRequest;
import com.ledger.dto.TransactionResponse;
import com.ledger.exception.IdempotencyConflictException;
import com.ledger.repository.IdempotencyRecordRepository;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;

@Service
public class LedgerOrchestrator {
    private static final long IDEMPOTENCY_RETENTION_DAYS = 1;

    private final RedissonClient redissonClient;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final LedgerValidationService validationService;
    private final LedgerEngineService ledgerEngineService;
    private final ObjectMapper objectMapper;

    public LedgerOrchestrator(RedissonClient redissonClient,
                              IdempotencyRecordRepository idempotencyRecordRepository,
                              LedgerValidationService validationService,
                              LedgerEngineService ledgerEngineService,
                              ObjectMapper objectMapper) {
        this.redissonClient = redissonClient;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.validationService = validationService;
        this.ledgerEngineService = ledgerEngineService;
        this.objectMapper = objectMapper;
    }

    public TransactionResponse processTransaction(PostTransactionRequest request) {
        validationService.validate(request);
        String requestHash = hashRequest(request);
        RLock lock = redissonClient.getLock("lock:txn:" + request.getIdempotencyKey());
        if (!lock.tryLock()) {
            throw new IdempotencyConflictException("A transaction with this idempotency key is already in progress");
        }

        try {
            IdempotencyRecord existing = idempotencyRecordRepository.findById(request.getIdempotencyKey())
                    .orElse(null);
            if (existing != null) {
                if (!existing.getRequestHash().equals(requestHash)) {
                    throw new IdempotencyConflictException(
                            "Idempotency key has already been used with a different transaction");
                }
                if (existing.getStatus() == IdempotencyStatus.COMMITTED) {
                    return deserializeResponse(existing.getResponsePayload());
                }
                if (existing.getStatus() == IdempotencyStatus.PENDING) {
                    throw new IdempotencyConflictException("Transaction with this idempotency key is pending");
                }
                throw new IdempotencyConflictException("Transaction with this idempotency key was rejected");
            }

            OffsetDateTime now = OffsetDateTime.now();
            idempotencyRecordRepository.saveAndFlush(new IdempotencyRecord(
                    request.getIdempotencyKey(), IdempotencyStatus.PENDING, requestHash, null,
                    now, now.plusDays(IDEMPOTENCY_RETENTION_DAYS)));

            TransactionResponse response = ledgerEngineService.processTransaction(request);
            IdempotencyRecord pending = idempotencyRecordRepository.findById(request.getIdempotencyKey())
                    .orElseThrow(() -> new IllegalStateException("Pending idempotency record disappeared"));
            pending.markCommitted(serializeResponse(response));
            idempotencyRecordRepository.saveAndFlush(pending);
            return response;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private String hashRequest(PostTransactionRequest request) {
        StringBuilder canonical = new StringBuilder();
        appendCanonical(canonical, request.getIdempotencyKey());
        appendCanonical(canonical, request.getNarration());
        canonical.append(request.getPostings().size()).append(':');
        for (PostingRequest posting : request.getPostings()) {
            appendCanonical(canonical, posting.getAccountId().toString());
            appendCanonical(canonical, posting.getDirection().name());
            appendCanonical(canonical, validationService.normalizeAmount(posting.getAmount()).toPlainString());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private void appendCanonical(StringBuilder canonical, String value) {
        canonical.append(value.length()).append(':').append(value);
    }

    private String serializeResponse(TransactionResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize transaction response", exception);
        }
    }

    private TransactionResponse deserializeResponse(String payload) {
        if (payload == null) {
            throw new IllegalStateException("Committed idempotency record has no cached response");
        }
        try {
            return objectMapper.readValue(payload, TransactionResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not deserialize cached transaction response", exception);
        }
    }
}
