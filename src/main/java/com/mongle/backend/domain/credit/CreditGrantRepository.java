package com.mongle.backend.domain.credit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface CreditGrantRepository extends JpaRepository<CreditGrant, Long> {
    Optional<CreditGrant> findFirstByUserIdAndSourceTypeOrderByIdAsc(Long userId, CreditSource sourceType);
    Optional<CreditGrant> findBySourceTypeAndSourceHash(CreditSource type, String hash);
    // Apply priority before pagination; retain grant order within free and paid groups.
    @Query("select g from CreditGrant g where g.userId=:userId and g.available>0 "
            + "order by case when g.sourceType=com.mongle.backend.domain.credit.CreditSource.PAYMENT then 1 else 0 end,g.id")
    List<CreditGrant> available(@Param("userId") Long userId, Pageable page);
}
