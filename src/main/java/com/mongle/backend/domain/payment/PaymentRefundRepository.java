package com.mongle.backend.domain.payment;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface PaymentRefundRepository extends JpaRepository<PaymentRefundRequest, Long> {
    Optional<PaymentRefundRequest> findByUserIdAndRequestId(Long userId, String requestId);
    Optional<PaymentRefundRequest> findByUserIdAndReceiptId(Long userId, String receiptId);
    boolean existsByGrantId(Long grantId);
    boolean existsByUserId(Long userId);

    @Query("""
            select r from PaymentRefundRequest r join fetch r.order join fetch r.grant
            where r.userId = :user and (:before is null or r.id < :before)
            order by r.id desc
            """)
    List<PaymentRefundRequest> page(@Param("user") Long userId, @Param("before") Long before, Pageable page);

    @Query(value = """
            select count(*) from credit_grants g
            where g.user_id = :user and g.source_type = 'PAYMENT' and (g.available > 0 or g.reserved > 0)
              and not exists (
                select 1 from payment_refund_requests r join payment_orders o on o.id = r.order_id
                where r.user_id = g.user_id and r.grant_id = g.id and o.user_id = g.user_id
                  and o.credit_grant_id = g.id and o.state in ('PAID', 'PAYMENT_REVIEW')
                  and r.available_at_request + r.reserved_at_request >= g.available + g.reserved
              )
            """, nativeQuery = true)
    long uncoveredPaidGrants(@Param("user") Long userId);
}
