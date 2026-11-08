package com.mongle.backend.domain.credit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface CreditGrantRepository extends JpaRepository<CreditGrant, Long> {
    Optional<CreditGrant> findFirstByUserIdAndSourceTypeOrderByIdAsc(Long userId, CreditSource sourceType);
    Optional<CreditGrant> findBySourceTypeAndSourceHash(CreditSource type, String hash);
    String SPENDABLE_GRANTS = """
            from credit_grants g where g.user_id = :userId and g.available > 0
            and not exists (select 1 from payment_refund_requests r
                left join payment_refund_resolutions s on s.refund_id = r.id
                where r.grant_id = g.id and s.refund_id is null)
            """;

    @Query(value = "select coalesce(sum(g.available), 0) " + SPENDABLE_GRANTS, nativeQuery = true)
    long spendable(@Param("userId") Long userId);

    // Apply priority before pagination; retain grant order within free and paid groups.
    @Query(value = "select g.* " + SPENDABLE_GRANTS
            + "order by case when g.source_type = 'PAYMENT' then 1 else 0 end, g.id", nativeQuery = true)
    List<CreditGrant> available(@Param("userId") Long userId, Pageable page);
}
