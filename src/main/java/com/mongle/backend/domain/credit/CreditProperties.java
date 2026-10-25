package com.mongle.backend.domain.credit;

import com.mongle.backend.global.error.BusinessException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("mongle.credit")
public record CreditProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("NO_EXPIRY") ExpiryPolicy expiryPolicy, Long signupAmount,
        Long dreamImageGeneration, Long dreamImageRegeneration,
        Long worldImageGeneration, Long worldImageRegeneration) {
    public enum ExpiryPolicy { UNDECIDED, NO_EXPIRY }
    public CreditProperties {
        if (expiryPolicy == null || (enabled && expiryPolicy != ExpiryPolicy.NO_EXPIRY))
            throw new IllegalArgumentException("크레딧 활성화 전 지원하는 유효기간 정책을 명시해야 합니다.");
        if (signupAmount != null && signupAmount < 0)
            throw new IllegalArgumentException("가입 지급량은 음수일 수 없습니다.");
        for (Long amount : new Long[]{dreamImageGeneration, dreamImageRegeneration,
                worldImageGeneration, worldImageRegeneration}) {
            if (amount != null && amount < 1)
                throw new IllegalArgumentException("이미지 사용량은 양수여야 합니다.");
        }
    }
    public void requireEnabled() {
        if (!enabled) throw new BusinessException(CreditErrorCode.UNAVAILABLE);
    }
    public long cost(CreditUsage usage) {
        requireEnabled();
        if (usage == null) throw new BusinessException(CreditErrorCode.INVALID_INPUT);
        Long amount = switch (usage) {
            case DREAM_IMAGE_GENERATION -> dreamImageGeneration;
            case DREAM_IMAGE_REGENERATION -> dreamImageRegeneration;
            case WORLD_IMAGE_GENERATION -> worldImageGeneration;
            case WORLD_IMAGE_REGENERATION -> worldImageRegeneration;
        };
        if (amount == null) throw new BusinessException(CreditErrorCode.UNAVAILABLE);
        return amount;
    }
}
