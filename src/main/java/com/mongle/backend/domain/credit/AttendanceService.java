package com.mongle.backend.domain.credit;

import com.mongle.backend.global.error.BusinessException;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AttendanceService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final CreditService credits;
    private final CreditGrantRepository grants;
    private final CreditProperties creditProperties;
    private final AttendanceProperties properties;
    private final Clock authClock;

    public AttendanceStatus status(Long userId) {
        credits.requireUser(userId, false);
        LocalDate date = today();
        var prior = priorGrant(userId, sourceKey(userId, date));
        return new AttendanceStatus(
                date,
                prior.isPresent(),
                prior.map(CreditGrant::getAmount).orElseGet(properties::amount),
                prior.isEmpty() && creditProperties.enabled() && properties.enabled(),
                nextClaimAt(date));
    }

    @Transactional
    public AttendanceClaim claim(Long userId) {
        // 모든 크레딧 변경과 동일하게 사용자부터 잠그고, 대기 중 자정을 넘었으면 새 날짜를 사용한다.
        credits.requireUser(userId, true);
        LocalDate date = today();
        String key = sourceKey(userId, date);
        var prior = priorGrant(userId, key);
        if (prior.isPresent()) {
            // 보상량 변경·기능 비활성 뒤에도 기존 수령의 재확인은 추가 지급 없이 성공한다.
            return new AttendanceClaim(
                    date, prior.get().getAmount(), true, credits.balance(userId), nextClaimAt(date));
        }
        creditProperties.requireEnabled();
        if (!properties.enabled()) throw new BusinessException(CreditErrorCode.UNAVAILABLE);
        var receipt = credits.grant(userId, CreditSource.ATTENDANCE, key, properties.amount());
        return new AttendanceClaim(
                date, receipt.amount(), false, credits.balance(userId), nextClaimAt(date));
    }

    private Optional<CreditGrant> priorGrant(Long userId, String key) {
        var prior = grants.findBySourceTypeAndSourceHash(CreditSource.ATTENDANCE, CreditRules.hash(key));
        prior.ifPresent(grant -> {
            if (!grant.getUserId().equals(userId) || !grant.getSourceKey().equals(key))
                throw new BusinessException(CreditErrorCode.REQUEST_CONFLICT);
        });
        return prior;
    }

    private LocalDate today() {
        return authClock.instant().atZone(ZONE).toLocalDate();
    }

    private static String sourceKey(Long userId, LocalDate date) {
        return "attendance:" + userId + ":" + date;
    }

    private static Instant nextClaimAt(LocalDate date) {
        return date.plusDays(1).atStartOfDay(ZONE).toInstant();
    }
}
