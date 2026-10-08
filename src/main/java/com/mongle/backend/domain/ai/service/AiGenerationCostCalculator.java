package com.mongle.backend.domain.ai.service;

import com.mongle.backend.domain.ai.config.AiPricingProperties;
import com.mongle.backend.domain.ai.dto.logging.AiGenerationCost;
import com.mongle.backend.domain.ai.dto.response.AiTokenUsage;
import com.mongle.backend.domain.ai.entity.AiCostStatus;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 수량과 가격표만으로 계산한다. HTTP·DB에 접근하지 않고 부동소수점 오차도 만들지 않는다. */
@Component
public class AiGenerationCostCalculator {
    private static final BigDecimal MILLION = new BigDecimal("1000000");
    // 변경 가능한 객체를 노출하지 않는 설정 레코드이므로 모든 호출에서 공유할 수 있다.
    private final AiPricingProperties properties;

    public AiGenerationCostCalculator(AiPricingProperties properties) {
        this.properties = properties;
    }

    public AiGenerationCost calculate(@Nullable String modelName, AiTokenUsage usage) {
        // 요청 모델명과 실제 응답 모델명은 다를 수 있다. 응답 모델이 확인될 때만 단가를 선택한다.
        var price = modelName == null ? null : properties.models().get(modelName);
        if (price == null) {
            return new AiGenerationCost(null, AiCostStatus.PRICE_MISSING, null, null, null, null, null);
        }
        // 캐시 비율을 모르면 일반 입력/캐시 입력을 정확히 나눌 수 없어 비용을 보류한다.
        BigDecimal amount = null;
        var status = AiCostStatus.USAGE_MISSING;
        if (usage.inputTokens() != null && usage.outputTokens() != null && usage.cachedInputTokens() != null) {
            // 캐시 입력은 전체 입력의 부분집합이다. 일반 입력에서 먼저 빼 중복 과금을 막는다.
            long uncached = (long) usage.inputTokens() - usage.cachedInputTokens();
            var inputCost = price.inputUsdPerMillion().multiply(BigDecimal.valueOf(uncached));
            var cachedCost = price.cachedInputUsdPerMillion().multiply(BigDecimal.valueOf(usage.cachedInputTokens()));
            // 출력에 포함된 reasoningTokens를 다시 더하지 않는다. totalTokens는 계산에 필요하지 않다.
            var outputCost = price.outputUsdPerMillion().multiply(BigDecimal.valueOf(usage.outputTokens()));
            amount = inputCost.add(cachedCost).add(outputCost).divide(MILLION, 8, RoundingMode.HALF_UP);
            status = AiCostStatus.CALCULATED;
        }
        // 사용량 미확정이어도 알고 있는 단가와 버전은 함께 기록한다.
        return new AiGenerationCost(amount, status, modelName, price.version(), price.inputUsdPerMillion(),
                price.outputUsdPerMillion(), price.cachedInputUsdPerMillion());
    }
}
