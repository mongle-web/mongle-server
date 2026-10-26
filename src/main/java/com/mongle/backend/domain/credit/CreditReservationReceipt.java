package com.mongle.backend.domain.credit;
import java.time.Instant;
/** 내부 생성 시도에 연결하는 예약. 중복 호출은 최초 수량과 기한을 유지한다. */
public record CreditReservationReceipt(Long reservationId, CreditUsage usage, String targetKey, long amount,
        CreditReservation.State state, Instant expiresAt, CreditReservation.ReleaseReason releaseReason) {
    static CreditReservationReceipt from(CreditReservation r) {
        return new CreditReservationReceipt(r.getId(), r.getUsage(), r.getTargetKey(), r.getAmount(),
                r.getState(), r.getExpiresAt(), r.getReleaseReason());
    }
}
