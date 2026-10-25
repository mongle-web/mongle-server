package com.mongle.backend.domain.credit;

import com.mongle.backend.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CreditReservationService {
    private final CreditService credits;
    private final CreditReservationRepository reservations;
    private final CreditGrantRepository grants;
    private final CreditAllocationRepository allocations;
    private final CreditLedgerRepository ledger;
    private final CreditProperties properties;
    private final Clock authClock;

    /** 이미지 시도 생성 트랜잭션에 참여한다. 외부 AI/스토리지 호출은 커밋 이후에 실행한다. */
    @Transactional
    public CreditReservationReceipt reserve(Long userId, String requestKey, CreditUsage usage,
            String targetKey, Instant expiresAt) {
        String hash = CreditRules.hash(requestKey);
        String targetHash = CreditRules.hash(targetKey);
        if (usage == null) throw new BusinessException(CreditErrorCode.INVALID_INPUT);
        credits.requireUser(userId, true);
        var prior = reservations.findByUserIdAndRequestHash(userId, hash);
        if (prior.isPresent()) {
            var r = prior.get();
            if (!r.getRequestKey().equals(requestKey) || r.getUsage() != usage || !r.getTargetKey().equals(targetKey))
                throw new BusinessException(CreditErrorCode.REQUEST_CONFLICT);
            return CreditReservationReceipt.from(r);
        }
        if (expiresAt == null) throw new BusinessException(CreditErrorCode.INVALID_INPUT);
        expiresAt = expiresAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (!expiresAt.isAfter(authClock.instant())) throw new BusinessException(CreditErrorCode.INVALID_INPUT);
        if (reservations.existsByUserIdAndUsageAndTargetHash(userId, usage, targetHash))
            throw new BusinessException(CreditErrorCode.REQUEST_CONFLICT);
        long amount = properties.cost(usage);
        var account = credits.account(userId);
        account.reserve(amount);
        var reservation = reservations.saveAndFlush(CreditReservation.create(userId, requestKey, usage, targetKey, amount, expiresAt));
        long remaining = amount;
        while (remaining > 0) {
            var sources = grants.available(userId, PageRequest.of(0, 100));
            if (sources.isEmpty()) throw new IllegalStateException("크레딧 계정과 지급 출처의 잔액 불일치");
            for (var source : sources) {
                long take = Math.min(remaining, source.getAvailable());
                source.reserve(take);
                allocations.save(CreditAllocation.create(reservation, source, take));
                remaining -= take;
                if (remaining == 0) break;
            }
        }
        ledger.save(CreditLedger.entry(account, CreditLedger.Kind.RESERVE, null, reservation.getId(), null, usage, -amount, amount));
        ledger.flush();
        return CreditReservationReceipt.from(reservation);
    }

    public CreditReservationReceipt get(Long userId, Long reservationId) {
        credits.requireUser(userId, false);
        return CreditReservationReceipt.from(owned(userId, reservationId));
    }
    private CreditReservation owned(Long userId, Long id) {
        if (id == null || id < 1) throw new BusinessException(CreditErrorCode.INVALID_INPUT);
        return reservations.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(CreditErrorCode.NOT_FOUND));
    }
}
