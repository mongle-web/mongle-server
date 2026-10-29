package com.mongle.backend.domain.dream.image;

import com.mongle.backend.domain.credit.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Clock;

/** 사용자 잠금을 획득한 이미지 저장/삭제 트랜잭션 안에서만 원장을 변경한다. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ImageCreditTransactions {
    private final CreditReservationService reservations;
    private final CreditService credits;
    private final Clock authClock;
    private final ImageRegenerationPolicy regenerationPolicy;

    public void reserve(DreamImage image) {
        if (image.isFreeRegenerationAttempt()) {
            regenerationPolicy.requireFreeAttemptAllowed(image.getUser().getId(), CreditUsage.DREAM_IMAGE_REGENERATION);
            return;
        }
        var receipt = reservations.reserve(image.getUser().getId(),
                "dream-image:" + image.getAttemptId(),
                image.getStorageKey() != null ? CreditUsage.DREAM_IMAGE_REGENERATION : CreditUsage.DREAM_IMAGE_GENERATION,
                "dream-image:" + image.getAnalysis().getId() + ":" + image.getAttemptId(),
                image.getLeaseUntil());
        image.attachCreditReservation(receipt.reservationId());
    }

    /** 예외로 트랜잭션을 오염시키기 전에 예약·사용 보류를 확인한다. */
    public String settlementFailure(DreamImage image) {
        if (image.isFreeRegenerationAttempt())
            return credits.balance(image.getUser().getId()).spendingBlocked() ? "CREDIT_PAYMENT_REVIEW" : null;
        if (image.getCreditReservationId() == null) return "CREDIT_RESERVATION_MISSING";
        var receipt = reservations.get(image.getUser().getId(), image.getCreditReservationId());
        if (receipt.state() != CreditReservation.State.RESERVED
                || !receipt.expiresAt().isAfter(authClock.instant())) return "CREDIT_RESERVATION_EXPIRED";
        if (credits.balance(image.getUser().getId()).spendingBlocked()) return "CREDIT_PAYMENT_REVIEW";
        return null;
    }

    public void settle(DreamImage image, String key) {
        if (image.isFreeRegenerationAttempt()) return;
        reservations.settle(image.getUser().getId(), image.getCreditReservationId(), key);
    }

    public void release(DreamImage image) {
        if (image.getCreditReservationId() == null) return; // 이전 버전의 결과에는 예약이 없다.
        var receipt = reservations.get(image.getUser().getId(), image.getCreditReservationId());
        if (receipt.state() == CreditReservation.State.RESERVED) {
            reservations.release(image.getUser().getId(), receipt.reservationId(),
                    receipt.expiresAt().isAfter(authClock.instant())
                            ? CreditReservation.ReleaseReason.FAILURE : CreditReservation.ReleaseReason.EXPIRED);
        }
    }
}
