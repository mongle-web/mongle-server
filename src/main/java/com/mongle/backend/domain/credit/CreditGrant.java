package com.mongle.backend.domain.credit;

import com.mongle.backend.global.common.BaseCreatedEntity;
import jakarta.persistence.*;
import lombok.*;

@Getter
@Entity
@Table(name = "credit_grants", uniqueConstraints = @UniqueConstraint(name = "uk_credit_grant_source",
        columnNames = {"source_type", "source_hash"}),
        indexes = @Index(name = "idx_credit_grant_available", columnList = "user_id,id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditGrant extends BaseCreatedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Enumerated(EnumType.STRING) @Column(name = "source_type", nullable = false, length = 20) private CreditSource sourceType;
    @Column(name = "source_key", nullable = false, length = 128) private String sourceKey;
    @Column(name = "source_hash", nullable = false, length = 64) private String sourceHash;
    @Column(nullable = false) private long amount;
    @Column(nullable = false) private long available;
    @Column(nullable = false) private long reserved;
    @Column(nullable = false) private long consumed;

    static CreditGrant create(Long userId, CreditSource type, String key, long amount) {
        var g = new CreditGrant(); g.userId = userId; g.sourceType = type;
        g.sourceKey = CreditRules.key(key); g.sourceHash = CreditRules.hash(key);
        g.amount = amount; g.available = amount; return g;
    }
    void reserve(long amount) {
        if (amount < 1 || available < amount) throw new IllegalStateException("크레딧 지급 출처 잔액 불일치");
        available -= amount; reserved += amount;
    }
    void settle(long amount) { requireReserved(amount); reserved -= amount; consumed += amount; }
    void release(long amount) { requireReserved(amount); reserved -= amount; available += amount; }
    private void requireReserved(long amount) {
        if (amount < 1 || reserved < amount) throw new IllegalStateException("크레딧 지급 출처 예약 불일치");
    }
}
