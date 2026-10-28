package com.mongle.backend.domain.dream.image;

import com.mongle.backend.domain.credit.*;
import com.mongle.backend.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 무료 시도도 해당 재생성 가격 설정·기능 활성화·결제 보류를 확인한다. 사용자 잠금은 호출 트랜잭션이 보유한다. */
@Component
@RequiredArgsConstructor
public class ImageRegenerationPolicy {
    private final CreditProperties properties;
    private final CreditService credits;

    public void requireFreeAttemptAllowed(Long userId, CreditUsage usage) {
        properties.cost(usage);
        if (credits.balance(userId).spendingBlocked())
            throw new BusinessException(CreditErrorCode.PAYMENT_REVIEW);
    }

    public Long nextCost(boolean hasResult, boolean freeUsed, CreditUsage usage) {
        if (!hasResult) return null;
        Long price = properties.configuredCost(usage);
        if (price == null) return null;
        return freeUsed ? price : 0L;
    }
}
