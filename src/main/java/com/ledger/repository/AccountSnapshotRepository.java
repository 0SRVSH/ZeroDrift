package com.ledger.repository;

import com.ledger.domain.AccountSnapshot;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AccountSnapshotRepository extends JpaRepository<AccountSnapshot, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AccountSnapshot s WHERE s.accountId IN :accountIds ORDER BY s.accountId ASC")
    List<AccountSnapshot> lockAccountsForUpdate(@Param("accountIds") List<UUID> accountIds);
}
