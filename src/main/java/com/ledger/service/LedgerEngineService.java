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
import com.ledger.exception.AccountNotFoundException;
import com.ledger.exception.InsufficientFundsException;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class LedgerEngineService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(4);

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
        Map<UUID, Account> accountsById = new HashMap<>();
        for (Account account : accountRepository.findAllById(accountIds)) {
            accountsById.put(account.getId(), account);
        }
        if (accountsById.size() != accountIds.size()) {
            throw new AccountNotFoundException("One or more transaction accounts were not found");
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
            long snapshotCount = accountSnapshotRepository.countByAccountId(accountId);
            if (snapshotCount != account.getBucketCount()) {
                throw new IllegalStateException("Account " + accountId + " has " + snapshotCount
                        + " snapshots; expected " + account.getBucketCount());
            }
        }

        List<PostingPlan> plans = new ArrayList<>(request.getPostings().size());
        Set<UUID> accountsWithBalanceReductions = new HashSet<>();
        Map<UUID, Set<Integer>> selectedCreditBuckets = new HashMap<>();
        for (PostingRequest postingRequest : request.getPostings()) {
            Account account = accountsById.get(postingRequest.getAccountId());
            BigDecimal amount = validationService.normalizeAmount(postingRequest.getAmount());
            BigDecimal adjustment = isNormalIncrease(account.getAccountType(), postingRequest.getDirection())
                    ? amount : amount.negate();
            int selectedBucket = -1;
            if (adjustment.signum() > 0) {
                selectedBucket = account.getBucketCount() > 1
                        ? ThreadLocalRandom.current().nextInt(account.getBucketCount()) : 0;
                selectedCreditBuckets.computeIfAbsent(account.getId(), ignored -> new HashSet<>())
                        .add(selectedBucket);
            } else {
                accountsWithBalanceReductions.add(account.getId());
            }
            plans.add(new PostingPlan(postingRequest, amount, adjustment, selectedBucket));
        }

        Map<BucketKey, AccountSnapshot> snapshotsByBucket = new LinkedHashMap<>();
        Map<BucketKey, BigDecimal> resultingBalances = new HashMap<>();
        for (UUID accountId : accountIds) {
            Account account = accountsById.get(accountId);
            Set<Integer> bucketIndexes = new HashSet<>();
            if (accountsWithBalanceReductions.contains(accountId)) {
                for (int index = 0; index < account.getBucketCount(); index++) {
                    bucketIndexes.add(index);
                }
            } else {
                bucketIndexes.addAll(selectedCreditBuckets.getOrDefault(accountId, Set.of()));
            }
            for (int bucketIndex : bucketIndexes.stream().sorted().toList()) {
                AccountSnapshot snapshot = accountSnapshotRepository.lockBucketForUpdate(accountId, bucketIndex)
                        .orElseThrow(() -> new AccountNotFoundException(
                                "Account has no snapshot for bucket " + bucketIndex + ": " + accountId));
                BucketKey key = new BucketKey(accountId, bucketIndex);
                snapshotsByBucket.put(key, snapshot);
                resultingBalances.put(key, snapshot.getClearedBalance());
            }
        }

        Map<PostingPlan, List<PostingAllocation>> allocationsByPlan = new IdentityHashMap<>();
        for (PostingPlan plan : plans) {
            if (plan.adjustment().signum() > 0) {
                BucketKey key = new BucketKey(plan.request().getAccountId(), plan.selectedBucketIndex());
                resultingBalances.compute(key, (ignored, balance) -> balance.add(plan.adjustment()));
                allocationsByPlan.put(plan, List.of(new PostingAllocation(
                        plan.selectedBucketIndex(), plan.amount())));
            }
        }
        for (PostingPlan plan : plans) {
            if (plan.adjustment().signum() >= 0) {
                continue;
            }
            UUID accountId = plan.request().getAccountId();
            BigDecimal remaining = plan.amount();
            List<PostingAllocation> allocations = new ArrayList<>();
            for (AccountSnapshot snapshot : snapshotsByBucket.values().stream()
                    .filter(candidate -> candidate.getAccountId().equals(accountId))
                    .sorted(Comparator.comparingInt(AccountSnapshot::getBucketIndex))
                    .toList()) {
                BucketKey key = new BucketKey(accountId, snapshot.getBucketIndex());
                BigDecimal available = resultingBalances.get(key).max(ZERO);
                BigDecimal portion = available.min(remaining);
                if (portion.signum() > 0) {
                    resultingBalances.put(key, resultingBalances.get(key).subtract(portion));
                    allocations.add(new PostingAllocation(snapshot.getBucketIndex(), portion));
                    remaining = remaining.subtract(portion);
                }
                if (remaining.signum() == 0) {
                    break;
                }
            }
            if (remaining.signum() > 0) {
                throw new InsufficientFundsException(
                        "Transaction would make the normal balance negative for account " + accountId);
            }
            allocationsByPlan.put(plan, List.copyOf(allocations));
        }

        OffsetDateTime postedAt = OffsetDateTime.now();
        JournalEntry journalEntry = journalEntryRepository.saveAndFlush(new JournalEntry(
                request.getIdempotencyKey(), request.getNarration(), JournalEntryStatus.COMMITTED, postedAt));

        List<Posting> postings = new ArrayList<>();
        List<PostingResponse> postingResponses = new ArrayList<>(plans.size());
        Set<BucketKey> changedBuckets = new HashSet<>();
        int sequenceOrder = 0;
        for (PostingPlan plan : plans) {
            for (PostingAllocation allocation : allocationsByPlan.get(plan)) {
                postings.add(new Posting(journalEntry.getId(), plan.request().getAccountId(),
                        allocation.bucketIndex(), plan.request().getDirection(), allocation.amount(),
                        sequenceOrder++, postedAt));
                changedBuckets.add(new BucketKey(plan.request().getAccountId(), allocation.bucketIndex()));
            }
            postingResponses.add(new PostingResponse(plan.request().getAccountId(),
                    plan.request().getDirection(), plan.amount()));
        }
        postingRepository.saveAllAndFlush(postings);

        for (BucketKey key : changedBuckets) {
            Long postingSequence = postingRepository.findMaxPostingSequence(
                    journalEntry.getId(), key.accountId(), key.bucketIndex());
            if (postingSequence == null) {
                throw new IllegalStateException("Persisted posting is missing for account " + key.accountId());
            }
            snapshotsByBucket.get(key).applyClearedBalance(
                    resultingBalances.get(key), journalEntry.getId(), postingSequence, postedAt);
        }
        accountSnapshotRepository.flush();

        return new TransactionResponse(journalEntry.getId(), journalEntry.getIdempotencyKey(),
                journalEntry.getStatus(), journalEntry.getPostedAt(), postingResponses);
    }

    private boolean isNormalIncrease(AccountType accountType, PostingDirection direction) {
        boolean debitNormal = accountType == AccountType.ASSET || accountType == AccountType.EXPENSE;
        return debitNormal == (direction == PostingDirection.DEBIT);
    }

    private record BucketKey(UUID accountId, int bucketIndex) {
    }

    private record PostingPlan(PostingRequest request, BigDecimal amount, BigDecimal adjustment,
                               int selectedBucketIndex) {
    }

    private record PostingAllocation(int bucketIndex, BigDecimal amount) {
    }
}
