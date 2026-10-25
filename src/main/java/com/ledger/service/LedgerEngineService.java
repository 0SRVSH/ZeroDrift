package com.ledger.service;

import com.ledger.domain.Account;
import com.ledger.domain.AccountSnapshot;
import com.ledger.domain.AccountType;
import com.ledger.domain.JournalEntry;
import com.ledger.domain.JournalEntryStatus;
import com.ledger.domain.Posting;
import com.ledger.domain.PostingDirection;
import com.ledger.dto.PostTransactionRequest;
import com.ledger.dto.PostingRequest;
import com.ledger.dto.PostingResponse;
import com.ledger.dto.TransactionResponse;
import com.ledger.repository.AccountRepository;
import com.ledger.repository.AccountSnapshotRepository;
import com.ledger.repository.JournalEntryRepository;
import com.ledger.repository.PostingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LedgerEngineService {
    private final LedgerValidationService validationService;
    private final AccountRepository accountRepository;
    private final AccountSnapshotRepository accountSnapshotRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final PostingRepository postingRepository;

    public LedgerEngineService(LedgerValidationService validationService, AccountRepository accountRepository,
                               AccountSnapshotRepository accountSnapshotRepository,
                               JournalEntryRepository journalEntryRepository, PostingRepository postingRepository) {
        this.validationService = validationService;
        this.accountRepository = accountRepository;
        this.accountSnapshotRepository = accountSnapshotRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.postingRepository = postingRepository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransactionResponse processTransaction(PostTransactionRequest request) {
        validationService.validate(request);

        List<UUID> accountIds = request.getPostings().stream()
                .map(PostingRequest::getAccountId)
                .distinct()
                .sorted()
                .toList();
        List<AccountSnapshot> snapshots = accountSnapshotRepository.lockAccountsForUpdate(accountIds);
        Map<UUID, AccountSnapshot> snapshotsByAccountId = new HashMap<>();
        for (AccountSnapshot snapshot : snapshots) {
            snapshotsByAccountId.put(snapshot.getAccountId(), snapshot);
        }

        Map<UUID, Account> accountsById = new HashMap<>();
        for (Account account : accountRepository.findAllById(accountIds)) {
            accountsById.put(account.getId(), account);
        }
        if (accountsById.size() != accountIds.size()) {
            throw new IllegalArgumentException("Transaction references an unknown account");
        }
        if (snapshotsByAccountId.size() != accountIds.size()) {
            throw new IllegalArgumentException("Every referenced account must have an account snapshot");
        }

        String currency = null;
        for (UUID accountId : accountIds) {
            Account account = accountsById.get(accountId);
            if (!account.isActive()) {
                throw new IllegalArgumentException("Transaction references an inactive account: " + accountId);
            }
            if (currency == null) {
                currency = account.getCurrency();
            } else if (!currency.equals(account.getCurrency())) {
                throw new IllegalArgumentException("All accounts in a transaction must use the same currency");
            }
        }

        Map<UUID, BigDecimal> resultingBalances = new HashMap<>();
        for (UUID accountId : accountIds) {
            resultingBalances.put(accountId, snapshotsByAccountId.get(accountId).getClearedBalance());
        }
        for (PostingRequest posting : request.getPostings()) {
            AccountType accountType = accountsById.get(posting.getAccountId()).getAccountType();
            BigDecimal amount = validationService.normalizeAmount(posting.getAmount());
            BigDecimal adjustment = isNormalIncrease(accountType, posting.getDirection()) ? amount : amount.negate();
            resultingBalances.compute(posting.getAccountId(), (accountId, balance) -> balance.add(adjustment));
        }
        for (Map.Entry<UUID, BigDecimal> entry : resultingBalances.entrySet()) {
            if (entry.getValue().signum() < 0) {
                throw new InsufficientFundsException(
                        "Transaction would make the normal balance negative for account " + entry.getKey());
            }
        }

        OffsetDateTime postedAt = OffsetDateTime.now();
        JournalEntry journalEntry = journalEntryRepository.saveAndFlush(new JournalEntry(
                request.getIdempotencyKey(), request.getNarration(), JournalEntryStatus.COMMITTED, postedAt));

        List<Posting> postings = new ArrayList<>(request.getPostings().size());
        List<PostingResponse> postingResponses = new ArrayList<>(request.getPostings().size());
        for (int index = 0; index < request.getPostings().size(); index++) {
            PostingRequest postingRequest = request.getPostings().get(index);
            BigDecimal amount = validationService.normalizeAmount(postingRequest.getAmount());
            postings.add(new Posting(journalEntry.getId(), postingRequest.getAccountId(),
                    postingRequest.getDirection(), amount, index, postedAt));
            postingResponses.add(new PostingResponse(postingRequest.getAccountId(),
                    postingRequest.getDirection(), amount));
        }
        postingRepository.saveAllAndFlush(postings);

        for (UUID accountId : accountIds) {
            snapshotsByAccountId.get(accountId).applyClearedBalance(
                    resultingBalances.get(accountId), journalEntry.getId(), postedAt);
        }
        accountSnapshotRepository.flush();

        return new TransactionResponse(journalEntry.getId(), journalEntry.getIdempotencyKey(),
                journalEntry.getStatus(), journalEntry.getPostedAt(), postingResponses);
    }

    private boolean isNormalIncrease(AccountType accountType, PostingDirection direction) {
        boolean debitNormal = accountType == AccountType.ASSET || accountType == AccountType.EXPENSE;
        return debitNormal == (direction == PostingDirection.DEBIT);
    }
}
