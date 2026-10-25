package com.ledger.repository;

import com.ledger.domain.AccountSnapshot;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountSnapshotRepository extends JpaRepository<AccountSnapshot, UUID> {
    List<AccountSnapshot> findAllByAccountIdOrderByBucketIndexAsc(UUID accountId);

    long countByAccountId(UUID accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AccountSnapshot s WHERE s.accountId = :accountId "
            + "AND s.bucketIndex = :bucketIndex")
    Optional<AccountSnapshot> lockBucketForUpdate(@Param("accountId") UUID accountId,
                                                  @Param("bucketIndex") int bucketIndex);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AccountSnapshot s ORDER BY s.accountId ASC, s.bucketIndex ASC")
    List<AccountSnapshot> lockAllForCheckpoint();

    @Query(value = """
            SELECT COALESCE(SUM(CASE
                WHEN (a.account_type IN ('ASSET', 'EXPENSE') AND p.direction = 'DEBIT')
                  OR (a.account_type IN ('LIABILITY', 'EQUITY', 'REVENUE') AND p.direction = 'CREDIT')
                THEN p.amount ELSE -p.amount
            END), 0) AS "clearedDelta",
            COALESCE(MAX(p.posting_sequence), 0) AS "maxPostingSequence",
            (SELECT latest.journal_entry_id
             FROM postings latest
             WHERE latest.account_id = :accountId
               AND latest.bucket_index = :bucketIndex
               AND latest.posting_sequence > :lastPostingSequence
             ORDER BY latest.posting_sequence DESC
             LIMIT 1) AS "lastJournalEntryId"
            FROM postings p
            JOIN accounts a ON a.id = p.account_id
            WHERE p.account_id = :accountId
              AND p.bucket_index = :bucketIndex
              AND p.posting_sequence > :lastPostingSequence
            """, nativeQuery = true)
    Optional<PostingCheckpoint> findCheckpoint(@Param("accountId") UUID accountId,
                                               @Param("bucketIndex") int bucketIndex,
                                               @Param("lastPostingSequence") long lastPostingSequence);

    interface PostingCheckpoint {
        BigDecimal getClearedDelta();

        Long getMaxPostingSequence();

        UUID getLastJournalEntryId();
    }
}
