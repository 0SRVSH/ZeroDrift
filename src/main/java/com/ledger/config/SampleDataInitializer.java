package com.ledger.config;

import com.ledger.domain.Account;
import com.ledger.domain.AccountSnapshot;
import com.ledger.domain.AccountType;
import com.ledger.repository.AccountRepository;
import com.ledger.repository.AccountSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Component
public class SampleDataInitializer implements CommandLineRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(SampleDataInitializer.class);

    private final AccountRepository accountRepository;
    private final AccountSnapshotRepository accountSnapshotRepository;

    public SampleDataInitializer(AccountRepository accountRepository,
                                 AccountSnapshotRepository accountSnapshotRepository) {
        this.accountRepository = accountRepository;
        this.accountSnapshotRepository = accountSnapshotRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (accountRepository.count() != 0) {
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();
        List<Account> accounts = accountRepository.saveAllAndFlush(List.of(
                new Account("OPERATING-ASSET-001", "USD", AccountType.ASSET, true, now),
                new Account("OPERATING-REVENUE-001", "USD", AccountType.REVENUE, true, now)));

        List<AccountSnapshot> snapshots = accounts.stream()
                .map(account -> new AccountSnapshot(account.getId(), BigDecimal.ZERO.setScale(4),
                        BigDecimal.ZERO.setScale(4), 0L, null, now))
                .toList();
        accountSnapshotRepository.saveAll(snapshots);

        accounts.forEach(account -> LOGGER.info("Seeded sample account {} with id {}",
                account.getAccountNumber(), account.getId()));
    }
}
