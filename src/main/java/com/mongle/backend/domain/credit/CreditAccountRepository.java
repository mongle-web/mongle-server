package com.mongle.backend.domain.credit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CreditAccountRepository extends JpaRepository<CreditAccount, Long> {
    @Modifying
    @Query("update CreditAccount a set a.version = a.version + 1 where a.userId = :user")
    int invalidateSpendingSnapshot(@Param("user") Long userId);
}
