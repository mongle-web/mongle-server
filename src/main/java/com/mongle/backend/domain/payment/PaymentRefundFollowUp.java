package com.mongle.backend.domain.payment;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "payment_refund_follow_ups", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"refund_id", "operator_reference"}),
        @UniqueConstraint(columnNames = {"id", "refund_id"})
})
class PaymentRefundFollowUp {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long refundId;
    @Column(nullable = false)
    private long additionalAmount;
    @Column(nullable = false)
    private long confirmedTotal;
    @Column(nullable = false, length = 80)
    private String operatorReference;
    @Column(nullable = false, length = 64)
    private String snapshotHash;
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(nullable = false)
    private Instant confirmedAt;
}
