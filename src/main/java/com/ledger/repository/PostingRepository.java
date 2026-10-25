package com.ledger.repository;

import com.ledger.domain.Posting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PostingRepository extends JpaRepository<Posting, UUID> {
    List<Posting> findAllByJournalEntryIdOrderBySequenceOrderAsc(UUID journalEntryId);

    @Query("SELECT MAX(p.postingSequence) FROM Posting p WHERE p.journalEntryId = :journalEntryId "
            + "AND p.accountId = :accountId AND p.bucketIndex = :bucketIndex")
    Long findMaxPostingSequence(@Param("journalEntryId") UUID journalEntryId,
                                @Param("accountId") UUID accountId,
                                @Param("bucketIndex") int bucketIndex);
}
