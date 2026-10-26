package com.mongle.backend.domain.credit;

import com.mongle.backend.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Clock;

/** 스케줄러/복구 작업에서 호출할 제한된 스캔. 이 기반 이슈에서는 자동 스케줄을 시작하지 않는다. */
@Service
@RequiredArgsConstructor
public class CreditReservationRecovery {
    private final CreditReservationRepository reservations;
    private final CreditReservationService transactions;
    private final Clock authClock;

    @Transactional(propagation = Propagation.NEVER)
    public int recoverExpired(int batchSize) {
        if (batchSize < 1 || batchSize > 100) throw new BusinessException(CreditErrorCode.INVALID_INPUT);
        var due = reservations.expired(CreditReservation.State.RESERVED, authClock.instant(), PageRequest.of(0, batchSize));
        int released = 0;
        for (var reservation : due) {
            if (transactions.expire(reservation.getUserId(), reservation.getId())) released++;
        }
        return released;
    }
}
