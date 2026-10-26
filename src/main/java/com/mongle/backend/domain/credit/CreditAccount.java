package com.mongle.backend.domain.credit;

import com.mongle.backend.global.common.BaseEntity;
import com.mongle.backend.global.error.BusinessException;
import jakarta.persistence.*;
import lombok.*;

@Getter
@Entity
@Table(name = "credit_accounts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditAccount extends BaseEntity {
    @Id @Column(name = "user_id") private Long userId;
    @Column(nullable = false) private long available;
    @Column(nullable = false) private long reserved;

    @Version @Column(nullable = false) private long version;

    static CreditAccount create(Long userId) {
        var a = new CreditAccount(); a.userId = userId; return a;
    }
    void grant(long amount) {
        CreditRules.positive(amount);
        CreditRules.add(CreditRules.add(available, reserved), amount);
        available = CreditRules.add(available, amount);
    }
    void reserve(long amount) {
        CreditRules.positive(amount);
        if (available < amount) throw new BusinessException(CreditErrorCode.INSUFFICIENT);
        available -= amount; reserved = CreditRules.add(reserved, amount);
    }
    void settle(long amount) { requireReserved(amount); reserved -= amount; }
    void release(long amount) {
        requireReserved(amount); reserved -= amount; available = CreditRules.add(available, amount);
    }
    private void requireReserved(long amount) {
        CreditRules.positive(amount);
        if (reserved < amount) throw new IllegalStateException("크레딧 예약 잔액 불일치");
    }
}
