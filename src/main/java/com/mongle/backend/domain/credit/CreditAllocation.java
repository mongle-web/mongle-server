package com.mongle.backend.domain.credit;

import com.mongle.backend.global.common.BaseCreatedEntity;
import jakarta.persistence.*;
import lombok.*;

@Getter
@Entity
@Table(name = "credit_allocations", uniqueConstraints = @UniqueConstraint(name = "uk_credit_allocation_source",
        columnNames = {"reservation_id","grant_id"}),
        indexes = @Index(name = "idx_credit_allocation_grant", columnList = "grant_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditAllocation extends BaseCreatedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "reservation_id", nullable = false)
    private CreditReservation reservation;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "grant_id", nullable = false)
    private CreditGrant grant;
    @Column(nullable = false) private long amount;
    static CreditAllocation create(CreditReservation reservation, CreditGrant grant, long amount) {
        var a = new CreditAllocation(); a.reservation = reservation; a.grant = grant; a.amount = amount; return a;
    }
}
