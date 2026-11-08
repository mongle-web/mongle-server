package com.mongle.backend.domain.payment;

import com.mongle.backend.domain.credit.CreditGrant;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "payment_refund_requests")
public class PaymentRefundRequest {
    public enum Reason { STANDARD, RIGHTS_REVIEW }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 36)
    private String receiptId;
    @Column(nullable = false, length = 36)
    private String requestId;
    @Column(nullable = false)
    private Long userId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private PaymentOrder order;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "grant_id", nullable = false)
    private CreditGrant grant;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24)
    private Reason reason;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24)
    private RefundEstimate.Status initialStatus;
    @Column(nullable = false)
    private long availableAtRequest;
    @Column(nullable = false)
    private long reservedAtRequest;
    @Column(nullable = false)
    private long usedAtRequest;
    private Long estimatedAmount;
    private Integer feePercent;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(nullable = false)
    private Instant createdAt;

    static PaymentRefundRequest receive(PaymentOrder order, CreditGrant grant, UUID requestId,
            Reason reason, RefundEstimate estimate, Instant now) {
        var refund = new PaymentRefundRequest();
        refund.receiptId = UUID.randomUUID().toString();
        refund.requestId = requestId.toString();
        refund.userId = order.getUserId();
        refund.order = order;
        refund.grant = grant;
        refund.reason = reason;
        refund.initialStatus = estimate.status();
        refund.availableAtRequest = estimate.unusedCredits();
        refund.reservedAtRequest = estimate.reservedCredits();
        refund.usedAtRequest = estimate.usedCredits();
        refund.estimatedAmount = estimate.estimatedAmount();
        refund.feePercent = estimate.feePercent();
        refund.createdAt = now;
        return refund;
    }
}
