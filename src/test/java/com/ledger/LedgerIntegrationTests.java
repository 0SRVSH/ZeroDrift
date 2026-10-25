package com.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.domain.Account;
import com.ledger.domain.AccountSnapshot;
import com.ledger.domain.AccountType;
import com.ledger.domain.JournalEntry;
import com.ledger.domain.JournalEntryStatus;
import com.ledger.domain.Posting;
import com.ledger.domain.PostingDirection;
import com.ledger.dto.AccountBalanceResponse;
import com.ledger.dto.PostTransactionRequest;
import com.ledger.dto.PostingRequest;
import com.ledger.dto.TransactionResponse;
import com.ledger.repository.AccountRepository;
import com.ledger.repository.AccountSnapshotRepository;
import com.ledger.repository.JournalEntryRepository;
import com.ledger.repository.PostingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerIntegrationTests extends AbstractLedgerIntegrationTest {
    private static final BigDecimal TEN = new BigDecimal("10.0000");
    private static final BigDecimal ZERO = new BigDecimal("0.0000");

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountSnapshotRepository accountSnapshotRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private PostingRepository postingRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void balancedTransactionPersistsJournalPostingsAndUpdatedBalances() {
        AccountPair accounts = createAccountPair(new BigDecimal("100.0000"), new BigDecimal("25.0000"));
        PostTransactionRequest request = transfer(accounts, "happy-" + UUID.randomUUID(), TEN);

        ResponseEntity<TransactionResponse> response = submit(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TransactionResponse transaction = response.getBody();
        assertThat(transaction).isNotNull();
        assertThat(transaction.status()).isEqualTo(JournalEntryStatus.COMMITTED);
        assertThat(transaction.postings()).hasSize(2);
        assertBalance(accounts.source().getId(), new BigDecimal("90.0000"));
        assertBalance(accounts.destination().getId(), new BigDecimal("35.0000"));

        JournalEntry journalEntry = journalEntryRepository.findById(transaction.journalEntryId()).orElseThrow();
        assertThat(journalEntry.getIdempotencyKey()).isEqualTo(request.getIdempotencyKey());
        assertThat(journalEntry.getNarration()).isEqualTo(request.getNarration());
        List<Posting> postings =
                postingRepository.findAllByJournalEntryIdOrderBySequenceOrderAsc(journalEntry.getId());
        assertThat(postings).hasSize(2);
        assertThat(postings).extracting(Posting::getAccountId)
                .containsExactly(accounts.source().getId(), accounts.destination().getId());
        assertThat(postings).extracting(Posting::getDirection)
                .containsExactly(PostingDirection.CREDIT, PostingDirection.DEBIT);
        assertThat(postings).extracting(Posting::getAmount)
                .containsExactly(TEN, TEN);
    }

    @Test
    void unbalancedTransactionReturns422WithoutWritingLedgerRowsOrChangingBalances() {
        AccountPair accounts = createAccountPair(new BigDecimal("100.0000"), new BigDecimal("25.0000"));
        PostTransactionRequest request = new PostTransactionRequest(
                "unbalanced-" + UUID.randomUUID(),
                "Unbalanced integration transaction",
                List.of(
                        new PostingRequest(accounts.source().getId(), PostingDirection.DEBIT, TEN),
                        new PostingRequest(accounts.destination().getId(), PostingDirection.CREDIT,
                                new BigDecimal("9.0000"))));
        long journalCountBefore = journalEntryRepository.count();
        long postingCountBefore = postingRepository.count();

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/ledger/transactions", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(journalEntryRepository.count()).isEqualTo(journalCountBefore);
        assertThat(postingRepository.count()).isEqualTo(postingCountBefore);
        assertBalance(accounts.source().getId(), new BigDecimal("100.0000"));
        assertBalance(accounts.destination().getId(), new BigDecimal("25.0000"));
    }

    @Test
    void retryWithSameIdempotencyKeyReturnsCachedResponseWithoutDuplicateRows() {
        AccountPair accounts = createAccountPair(new BigDecimal("100.0000"), new BigDecimal("25.0000"));
        PostTransactionRequest request = transfer(accounts, "retry-" + UUID.randomUUID(), TEN);

        ResponseEntity<TransactionResponse> firstResponse = submit(request);
        TransactionResponse original = firstResponse.getBody();
        assertThat(original).isNotNull();
        long journalCountAfterFirst = journalEntryRepository.count();
        long postingCountAfterFirst = postingRepository.count();
        ResponseEntity<TransactionResponse> retryResponse = submit(request);

        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retryResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retryResponse.getBody()).isEqualTo(original);
        assertThat(journalEntryRepository.count()).isEqualTo(journalCountAfterFirst);
        assertThat(postingRepository.count()).isEqualTo(postingCountAfterFirst);
        assertThat(journalEntryRepository.findById(original.journalEntryId())).isPresent();
        assertBalance(accounts.source().getId(), new BigDecimal("90.0000"));
        assertBalance(accounts.destination().getId(), new BigDecimal("35.0000"));
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void simultaneousTransfersInOppositeDirectionsCompleteWithoutDeadlocksOrBalanceDrift()
            throws Exception {
        int threadCount = 20;
        BigDecimal initialBalance = new BigDecimal("1000.0000");
        AccountPair accounts = createAccountPair(initialBalance, initialBalance);
        List<PostTransactionRequest> requests = new ArrayList<>(threadCount);
        for (int index = 0; index < threadCount; index++) {
            AccountPair direction = index % 2 == 0
                    ? accounts
                    : new AccountPair(accounts.destination(), accounts.source());
            requests.add(transfer(direction, "concurrent-" + UUID.randomUUID(), BigDecimal.ONE));
        }
        long journalCountBefore = journalEntryRepository.count();
        long postingCountBefore = postingRepository.count();

        List<ResponseEntity<String>> responses = executeConcurrently(requests, threadCount);

        assertThat(responses).hasSize(threadCount)
                .allSatisfy(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED));
        BigDecimal sourceBalance = balance(accounts.source().getId());
        BigDecimal destinationBalance = balance(accounts.destination().getId());
        assertThat(sourceBalance.add(destinationBalance))
                .isEqualByComparingTo(initialBalance.add(initialBalance));
        assertThat(journalEntryRepository.count()).isEqualTo(journalCountBefore + threadCount);
        assertThat(postingRepository.count()).isEqualTo(postingCountBefore + threadCount * 2L);
        for (ResponseEntity<String> response : responses) {
            TransactionResponse transaction = transaction(response);
            assertThat(postingRepository.findAllByJournalEntryIdOrderBySequenceOrderAsc(
                    transaction.journalEntryId())).hasSize(2);
        }
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void concurrentDebitsCannotOverdrawTheSourceAccount() throws Exception {
        int threadCount = 20;
        AccountPair accounts = createAccountPair(new BigDecimal("5.0000"), ZERO);
        List<PostTransactionRequest> requests = new ArrayList<>(threadCount);
        for (int index = 0; index < threadCount; index++) {
            requests.add(transfer(accounts, "depletion-" + UUID.randomUUID(), BigDecimal.ONE));
        }
        long journalCountBefore = journalEntryRepository.count();
        long postingCountBefore = postingRepository.count();

        List<ResponseEntity<String>> responses = executeConcurrently(requests, threadCount);

        assertThat(responses.stream().filter(response -> response.getStatusCode() == HttpStatus.CREATED).count())
                .isEqualTo(5);
        assertThat(responses.stream()
                .filter(response -> response.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY).count())
                .isEqualTo(15);
        assertThat(journalEntryRepository.count()).isEqualTo(journalCountBefore + 5);
        assertThat(postingRepository.count()).isEqualTo(postingCountBefore + 10);
        assertBalance(accounts.source().getId(), ZERO);
        assertBalance(accounts.destination().getId(), new BigDecimal("5.0000"));
    }

    private AccountPair createAccountPair(BigDecimal sourceBalance, BigDecimal destinationBalance) {
        OffsetDateTime now = OffsetDateTime.now();
        String accountSuffix = UUID.randomUUID().toString();
        List<Account> accounts = accountRepository.saveAllAndFlush(List.of(
                new Account("IT-SOURCE-" + accountSuffix, "USD", AccountType.ASSET, true, now),
                new Account("IT-DESTINATION-" + accountSuffix, "USD", AccountType.ASSET, true, now)));
        Account source = accounts.get(0);
        Account destination = accounts.get(1);
        accountSnapshotRepository.saveAllAndFlush(List.of(
                new AccountSnapshot(source.getId(), sourceBalance, ZERO, 0L, null, now),
                new AccountSnapshot(destination.getId(), destinationBalance, ZERO, 0L, null, now)));
        return new AccountPair(source, destination);
    }

    private PostTransactionRequest transfer(AccountPair accounts, String idempotencyKey, BigDecimal amount) {
        return new PostTransactionRequest(idempotencyKey, "Integration transfer", List.of(
                new PostingRequest(accounts.source().getId(), PostingDirection.CREDIT, amount),
                new PostingRequest(accounts.destination().getId(), PostingDirection.DEBIT, amount)));
    }

    private ResponseEntity<TransactionResponse> submit(PostTransactionRequest request) {
        return restTemplate.postForEntity("/api/v1/ledger/transactions", request, TransactionResponse.class);
    }

    private void assertBalance(UUID accountId, BigDecimal expectedBalance) {
        assertThat(balance(accountId)).isEqualByComparingTo(expectedBalance);
    }

    private BigDecimal balance(UUID accountId) {
        ResponseEntity<AccountBalanceResponse> response = restTemplate.getForEntity(
                "/api/v1/ledger/accounts/{accountId}/balance", AccountBalanceResponse.class, accountId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().clearedBalance();
    }

    private List<ResponseEntity<String>> executeConcurrently(
            List<PostTransactionRequest> requests, int threadCount) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>(threadCount);
        try {
            for (PostTransactionRequest request : requests) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to start concurrent requests");
                    }
                    return restTemplate.postForEntity("/api/v1/ledger/transactions", request, String.class);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            List<ResponseEntity<String>> responses = new ArrayList<>(threadCount);
            for (Future<ResponseEntity<String>> future : futures) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new AssertionError("Concurrent transaction requests did not complete within 60 seconds");
                }
                responses.add(future.get(remainingNanos, TimeUnit.NANOSECONDS));
            }
            return responses;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private TransactionResponse transaction(ResponseEntity<String> response) throws JsonProcessingException {
        assertThat(response.getBody()).isNotNull();
        return objectMapper.readValue(response.getBody(), TransactionResponse.class);
    }

    private record AccountPair(Account source, Account destination) {
    }
}
