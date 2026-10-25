package com.ledger.controller;

import com.ledger.domain.AccountSnapshot;
import com.ledger.domain.JournalEntry;
import com.ledger.dto.AccountBalanceResponse;
import com.ledger.dto.PostTransactionRequest;
import com.ledger.dto.TransactionDetailResponse;
import com.ledger.dto.TransactionResponse;
import com.ledger.exception.AccountNotFoundException;
import com.ledger.exception.JournalEntryNotFoundException;
import com.ledger.repository.AccountSnapshotRepository;
import com.ledger.repository.JournalEntryRepository;
import com.ledger.repository.PostingRepository;
import com.ledger.service.LedgerOrchestrator;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {
    private final LedgerOrchestrator ledgerOrchestrator;
    private final AccountSnapshotRepository accountSnapshotRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final PostingRepository postingRepository;

    public LedgerController(LedgerOrchestrator ledgerOrchestrator,
                            AccountSnapshotRepository accountSnapshotRepository,
                            JournalEntryRepository journalEntryRepository,
                            PostingRepository postingRepository) {
        this.ledgerOrchestrator = ledgerOrchestrator;
        this.accountSnapshotRepository = accountSnapshotRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.postingRepository = postingRepository;
    }

    @PostMapping("/transactions")
    public ResponseEntity<TransactionResponse> postTransaction(
            @Valid @RequestBody PostTransactionRequest request) {
        TransactionResponse response = ledgerOrchestrator.processTransaction(request);
        URI location = URI.create("/api/v1/ledger/transactions/" + response.journalEntryId());
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/accounts/{accountId}/balance")
    public AccountBalanceResponse getBalance(@PathVariable UUID accountId) {
        List<AccountSnapshot> snapshots = accountSnapshotRepository.findAllByAccountIdOrderByBucketIndexAsc(accountId);
        if (snapshots.isEmpty()) {
            throw new AccountNotFoundException("Account not found: " + accountId);
        }
        BigDecimal clearedBalance = BigDecimal.ZERO.setScale(4);
        BigDecimal pendingBalance = BigDecimal.ZERO.setScale(4);
        OffsetDateTime updatedAt = null;
        for (AccountSnapshot snapshot : snapshots) {
            clearedBalance = clearedBalance.add(snapshot.getClearedBalance());
            pendingBalance = pendingBalance.add(snapshot.getPendingBalance());
            if (updatedAt == null || snapshot.getUpdatedAt().isAfter(updatedAt)) {
                updatedAt = snapshot.getUpdatedAt();
            }
        }
        return new AccountBalanceResponse(accountId, clearedBalance, pendingBalance, updatedAt);
    }

    @GetMapping("/transactions/{id}")
    public TransactionDetailResponse getTransaction(@PathVariable UUID id) {
        JournalEntry journalEntry = journalEntryRepository.findById(id)
                .orElseThrow(() -> new JournalEntryNotFoundException("Transaction not found: " + id));
        return new TransactionDetailResponse(journalEntry,
                postingRepository.findAllByJournalEntryIdOrderBySequenceOrderAsc(id));
    }
}
