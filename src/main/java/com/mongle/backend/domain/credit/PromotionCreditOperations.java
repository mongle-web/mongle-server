package com.mongle.backend.domain.credit;

import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.error.BusinessException;

import lombok.RequiredArgsConstructor;

import org.springframework.transaction.annotation.Transactional;

/** 운영 단발 명령의 전용 빈이다. 일반 서버에서는 등록하지 않는다. */
@RequiredArgsConstructor
public class PromotionCreditOperations {
    private final CreditService credits;
    private final CreditProperties properties;
    private final CreditGrantRepository grants;
    private final UserRepository users;

    public static void validate(Long user, long amount, String requestKey) {
        if (user == null || user < 1) throw new BusinessException(CreditErrorCode.INVALID_INPUT);
        CreditRules.positive(amount);
        sourceKey(requestKey);
    }

    private static String sourceKey(String requestKey) {
        // 다른 프로모션 지급 요청과 식별자 공간을 분리한다. 입력 자체도 키 규칙을 따른다.
        CreditRules.key(requestKey);
        return CreditRules.key("test-credit:" + requestKey);
    }

    @Transactional(readOnly = true)
    public Result preview(Long user, long amount, String requestKey) {
        return inspect(user, amount, requestKey);
    }

    @Transactional
    public Result grant(Long user, long amount, String requestKey) {
        validate(user, amount, requestKey);
        // 다른 이미지/크레딧 변경과 동일하게 사용자 행부터 잠근다.
        users.findByIdForUpdate(user)
                .orElseThrow(() -> new BusinessException(CreditErrorCode.NOT_FOUND));
        var before = inspect(user, amount, requestKey);
        var receipt = credits.grant(user, CreditSource.PROMOTION, sourceKey(requestKey), amount);
        return new Result(before.alreadyGranted(), receipt.grantId(), credits.balance(user));
    }

    private Result inspect(Long user, long amount, String requestKey) {
        validate(user, amount, requestKey);
        properties.requireEnabled();
        var balance = credits.balance(user); // 존재 여부와 온보딩 상태도 검증한다. 행을 만들지 않는다.
        if (balance.spendingBlocked()) throw new BusinessException(CreditErrorCode.PAYMENT_REVIEW);
        String key = sourceKey(requestKey);
        var prior =
                grants.findBySourceTypeAndSourceHash(CreditSource.PROMOTION, CreditRules.hash(key));
        if (prior.isPresent()) {
            var grant = prior.get();
            if (!grant.getUserId().equals(user)
                    || !grant.getSourceKey().equals(key)
                    || grant.getAmount() != amount)
                throw new BusinessException(CreditErrorCode.REQUEST_CONFLICT);
            return new Result(true, grant.getId(), balance);
        }
        CreditRules.add(balance.total(), amount);
        return new Result(false, null, balance);
    }

    public record Result(boolean alreadyGranted, Long grantId, CreditBalance balance) {}
}
