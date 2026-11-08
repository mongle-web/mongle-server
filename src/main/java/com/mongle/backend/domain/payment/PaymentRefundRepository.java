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
    @Query(value = """
            select count(*) from payment_refund_requests r
            left join payment_refund_resolutions s on s.refund_id = r.id
            where r.grant_id = :grantId and (s.refund_id is null or s.status = 'COMPLETED')
            """, nativeQuery = true)
    long countOpenOrCompletedForGrant(@Param("grantId") Long grantId);
    @Query(value = """
            select count(*) from payment_refund_requests r
            left join payment_refund_resolutions s on s.refund_id = r.id
            where r.user_id = :userId and (s.refund_id is null or exists (
                select 1 from credit_payment_holds h where h.grant_id = r.grant_id and h.user_id = r.user_id
            ))
            """, nativeQuery = true)
    long countOpen(@Param("userId") Long userId);

    @Query(value = """
            select count(*) from payment_refund_evidence e
            join payment_refund_requests r on r.id = e.refund_id
            where r.order_id = :orderId and e.provider_transaction = :transaction
              and e.amount = :amount and e.full_cancellation = :full
            """, nativeQuery = true)
    long countMatchedCancellation(@Param("orderId") Long orderId, @Param("transaction") String transaction,
            @Param("amount") long amount, @Param("full") boolean full);

    @Query("""
            select r from PaymentRefundRequest r join fetch r.order join fetch r.grant left join fetch r.resolution
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
                  and not exists (select 1 from payment_refund_resolutions s where s.refund_id = r.id)
                  and r.available_at_request + r.reserved_at_request >= g.available + g.reserved
              )
            """, nativeQuery = true)
    long uncoveredPaidGrants(@Param("user") Long userId);
}
