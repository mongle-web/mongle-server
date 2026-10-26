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

    /** 성공 결과 저장과 동일한 트랜잭션에서 실행한다. 만료/해제된 예약으로 결과를 공개하면 안 된다. */
    @Transactional
    public CreditReservationReceipt settle(Long userId, Long reservationId, String resultKey) {
        CreditRules.key(resultKey);
        credits.requireUser(userId, true);
        var r = owned(userId, reservationId);
        if (r.getState() == CreditReservation.State.SETTLED) {
            if (!resultKey.equals(r.getResultKey())) throw new BusinessException(CreditErrorCode.REQUEST_CONFLICT);
            return CreditReservationReceipt.from(r);
        }
        if (r.getState() != CreditReservation.State.RESERVED || !r.getExpiresAt().isAfter(authClock.instant()))
            throw new BusinessException(CreditErrorCode.STATE_CONFLICT);
        var account = credits.account(userId);
        account.settle(r.getAmount());
        for (var allocation : checkedAllocations(r)) allocation.getGrant().settle(allocation.getAmount());
        r.settle(resultKey);
        ledger.save(CreditLedger.entry(account, CreditLedger.Kind.SETTLE, null, r.getId(), null, r.getUsage(), 0, -r.getAmount()));
        ledger.flush();
        return CreditReservationReceipt.from(r);
    }

    /** 생성 실패/취소에 대한 예약 해제이며 구매 결제 환불과는 별개다. 최초 해제 사유를 보존한다. */
    @Transactional
    public CreditReservationReceipt release(Long userId, Long reservationId, CreditReservation.ReleaseReason reason) {
        if (reason == null) throw new BusinessException(CreditErrorCode.INVALID_INPUT);
        credits.requireUser(userId, true);
        var r = owned(userId, reservationId);
        if (r.getState() == CreditReservation.State.RELEASED) return CreditReservationReceipt.from(r);
        if (r.getState() != CreditReservation.State.RESERVED) throw new BusinessException(CreditErrorCode.STATE_CONFLICT);
        if (reason == CreditReservation.ReleaseReason.EXPIRED && r.getExpiresAt().isAfter(authClock.instant()))
            throw new BusinessException(CreditErrorCode.STATE_CONFLICT);
        return releaseReserved(r, reason);
    }

    /** 복구 스캔의 각 항목을 독립 처리하고 잠금 획득 뒤 상태·기한을 다시 확인한다. */
    @Transactional
    public boolean expire(Long userId, Long reservationId) {
        credits.requireUser(userId, true);
        var r = owned(userId, reservationId);
        if (r.getState() != CreditReservation.State.RESERVED || r.getExpiresAt().isAfter(authClock.instant()))
            return false;
        releaseReserved(r, CreditReservation.ReleaseReason.EXPIRED);
        return true;
    }

    private CreditReservationReceipt releaseReserved(CreditReservation r, CreditReservation.ReleaseReason reason) {
        var account = credits.account(r.getUserId());
        account.release(r.getAmount());
        for (var allocation : checkedAllocations(r)) allocation.getGrant().release(allocation.getAmount());
        r.release(reason);
        ledger.save(CreditLedger.entry(account, CreditLedger.Kind.RELEASE, null, r.getId(), null, r.getUsage(), r.getAmount(), -r.getAmount()));
        ledger.flush();
        return CreditReservationReceipt.from(r);
    }

    private java.util.List<CreditAllocation> checkedAllocations(CreditReservation r) {
        var rows = allocations.forReservation(r.getId());
        long sum = 0;
        for (var row : rows) {
            if (!row.getGrant().getUserId().equals(r.getUserId())) throw new IllegalStateException("크레딧 지급 출처 소유권 불일치");
            sum = CreditRules.add(sum, row.getAmount());
        }
        if (sum != r.getAmount()) throw new IllegalStateException("크레딧 예약 배분 불일치");
        return rows;
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
