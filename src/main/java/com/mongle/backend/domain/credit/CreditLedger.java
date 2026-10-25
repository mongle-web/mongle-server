package com.mongle.backend.domain.credit;

import com.mongle.backend.global.common.BaseCreatedEntity;
import jakarta.persistence.*;
import lombok.*;

@Getter
@Entity
@Table(name = "credit_ledger", uniqueConstraints = @UniqueConstraint(name = "uk_credit_ledger_entry", columnNames = "entry_key"),
        indexes = @Index(name = "idx_credit_ledger_user_id", columnList = "user_id,id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditLedger extends BaseCreatedEntity {
    public enum Kind { GRANT, RESERVE, SETTLE, RELEASE }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "entry_key", nullable = false, length = 80) private String entryKey;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Kind kind;
    @Enumerated(EnumType.STRING) @Column(name = "source_type", length = 20) private CreditSource sourceType;
    @Enumerated(EnumType.STRING) @Column(length = 40) private CreditUsage usage;
    @Column(name = "grant_id") private Long grantId;
    @Column(name = "reservation_id") private Long reservationId;
    @Column(name = "available_delta", nullable = false) private long availableDelta;
    @Column(name = "reserved_delta", nullable = false) private long reservedDelta;
    @Column(name = "available_after", nullable = false) private long availableAfter;
    @Column(name = "reserved_after", nullable = false) private long reservedAfter;

    static CreditLedger entry(CreditAccount account, Kind kind, Long grantId, Long reservationId,
            CreditSource source, CreditUsage usage, long availableDelta, long reservedDelta) {
        var l = new CreditLedger(); l.userId = account.getUserId(); l.kind = kind;
        l.grantId = grantId; l.reservationId = reservationId; l.sourceType = source; l.usage = usage;
        l.entryKey = kind.name() + ":" + (grantId == null ? reservationId : grantId);
        l.availableDelta = availableDelta; l.reservedDelta = reservedDelta;
        l.availableAfter = account.getAvailable(); l.reservedAfter = account.getReserved(); return l;
    }
}
