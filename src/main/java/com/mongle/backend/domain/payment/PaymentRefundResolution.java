package com.mongle.backend.domain.payment;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "payment_refund_resolutions")
class PaymentRefundResolution {
    public enum Status { COMPLETED, REJECTED, WITHDRAWN }

    @Id @Column(name = "refund_id")
    private Long refundId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;
    @Column(nullable = false)
    private long refundedAmount;
    @Column(nullable = false)
    private long revokedCredits;
    @Column(nullable = false)
    private boolean releasedPaymentHold;
    @Column(nullable = false, length = 12)
    private String basis;
    @Column(nullable = false, length = 80)
    private String operatorReference;
    @Column(nullable = false, length = 64)
    private String snapshotHash;
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(nullable = false)
    private Instant resolvedAt;
}
