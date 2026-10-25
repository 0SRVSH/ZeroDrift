package com.ledger.repository;

import com.ledger.domain.Posting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PostingRepository extends JpaRepository<Posting, UUID> {
    List<Posting> findAllByJournalEntryIdOrderBySequenceOrderAsc(UUID journalEntryId);
}
