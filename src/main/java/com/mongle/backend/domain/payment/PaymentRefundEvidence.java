package com.mongle.backend.domain.payment;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "payment_refund_evidence")
class PaymentRefundEvidence {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long refundId;
    private Long followUpId;
    @Column(nullable = false, length = 40, unique = true)
    private String providerTransaction;
    @Column(nullable = false)
    private long amount;
    @Column(nullable = false)
    private boolean fullCancellation;
}
