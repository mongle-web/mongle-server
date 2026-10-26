package com.mongle.backend.domain.credit;

import com.mongle.backend.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Getter
@Entity
@Table(name = "credit_reservations", uniqueConstraints = {
    @UniqueConstraint(name = "uk_credit_reservation_request", columnNames = {"user_id","request_hash"}),
    @UniqueConstraint(name = "uk_credit_reservation_target", columnNames = {"user_id","`usage`","target_hash"})},
    indexes = @Index(name = "idx_credit_reservation_expiry", columnList = "state,expires_at,id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditReservation extends BaseEntity {
    public enum State { RESERVED, SETTLED, RELEASED }
    public enum ReleaseReason { FAILURE, CANCELLED, EXPIRED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "request_key", nullable = false, length = 128) private String requestKey;
    @Column(name = "request_hash", nullable = false, length = 64) private String requestHash;
    @Enumerated(EnumType.STRING) @Column(name = "`usage`", nullable = false, length = 40) private CreditUsage usage;
    @Column(name = "target_key", nullable = false, length = 128) private String targetKey;
    @Column(name = "target_hash", nullable = false, length = 64) private String targetHash;
    @Column(nullable = false) private long amount;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private State state;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "result_key", length = 128) private String resultKey;
    @Enumerated(EnumType.STRING) @Column(name = "release_reason", length = 20) private ReleaseReason releaseReason;

    @Version @Column(nullable = false) private long version;

    static CreditReservation create(Long userId, String key, CreditUsage usage, String target, long amount, Instant expiresAt) {
        var r = new CreditReservation(); r.userId = userId; r.requestKey = key; r.requestHash = CreditRules.hash(key);
        r.usage = usage; r.targetKey = target; r.targetHash = CreditRules.hash(target);
        r.amount = amount; r.expiresAt = expiresAt; r.state = State.RESERVED; return r;
    }
    void settle(String result) { state = State.SETTLED; resultKey = result; }
    void release(ReleaseReason reason) { state = State.RELEASED; releaseReason = reason; }
}
