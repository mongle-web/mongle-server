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
@Table(name = "payment_notification_recoveries")
class PaymentNotificationRecovery {
    @Id
    private Long orderId;
    @Column(nullable = false, length = 64, unique = true)
    private String eventHash;
    @Column(nullable = false, length = 80)
    private String operatorReference;
    @Column(nullable = false, length = 64)
    private String evidenceHash;
    @Column(nullable = false, length = 64)
    private String snapshotHash;
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(nullable = false)
    private Instant recoveredAt;

    static PaymentNotificationRecovery record(Long orderId, String eventHash, String operatorReference,
            String evidenceHash, String snapshotHash, Instant recoveredAt) {
        var recovery = new PaymentNotificationRecovery();
        recovery.orderId = orderId;
        recovery.eventHash = eventHash;
        recovery.operatorReference = operatorReference;
        recovery.evidenceHash = evidenceHash;
        recovery.snapshotHash = snapshotHash;
        recovery.recoveredAt = recoveredAt;
        return recovery;
    }
}
