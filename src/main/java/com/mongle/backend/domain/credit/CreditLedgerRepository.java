package com.mongle.backend.domain.credit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.util.List;
public interface CreditLedgerRepository extends JpaRepository<CreditLedger, Long> {
    @Query("select l from CreditLedger l where l.userId=:userId and (:beforeId is null or l.id<:beforeId) order by l.id desc")
    List<CreditLedger> page(@Param("userId") Long userId, @Param("beforeId") Long beforeId, Pageable page);
}
