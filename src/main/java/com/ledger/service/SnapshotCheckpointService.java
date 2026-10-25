package com.ledger.service;

import com.ledger.domain.AccountSnapshot;
import com.ledger.repository.AccountSnapshotRepository;
import com.ledger.repository.AccountSnapshotRepository.PostingCheckpoint;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class SnapshotCheckpointService {
    private final AccountSnapshotRepository accountSnapshotRepository;

    public SnapshotCheckpointService(AccountSnapshotRepository accountSnapshotRepository) {
        this.accountSnapshotRepository = accountSnapshotRepository;
    }

    @Scheduled(fixedDelayString = "${ledger.checkpoint.interval-ms:60000}")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void checkpointSnapshots() {
        List<AccountSnapshot> snapshots = accountSnapshotRepository.lockAllForCheckpoint();
        OffsetDateTime checkpointedAt = OffsetDateTime.now();
        for (AccountSnapshot snapshot : snapshots) {
            PostingCheckpoint checkpoint = accountSnapshotRepository.findCheckpoint(
                    snapshot.getAccountId(), snapshot.getBucketIndex(), snapshot.getLastPostingSequence())
                    .orElseThrow(() -> new IllegalStateException("Could not calculate snapshot checkpoint for "
                            + snapshot.getAccountId() + " bucket " + snapshot.getBucketIndex()));
            long maxPostingSequence = checkpoint.getMaxPostingSequence();
            if (maxPostingSequence > snapshot.getLastPostingSequence()) {
                snapshot.applyCheckpoint(checkpoint.getClearedDelta(), checkpoint.getLastJournalEntryId(),
                        maxPostingSequence, checkpointedAt);
            }
        }
        accountSnapshotRepository.flush();
    }
}
