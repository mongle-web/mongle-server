package com.mongle.backend.domain.ai.dto.logging;

import com.mongle.backend.domain.ai.entity.AiCostStatus;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;

/** 비용과 계산 당시의 단가를 함께 운반한다. 단가 변경 후에도 과거 계산 근거를 확인할 수 있다. */
public record AiGenerationCost(
        @Nullable BigDecimal amountUsd,       // 제공자 청구서가 아닌 사용량 기반 추정액. 미확정은 null.
        AiCostStatus status,
        @Nullable String pricingModel,        // 정확히 일치한 응답 모델명으로 단가를 조회한다.
        @Nullable String pricingVersion,      // 운영자가 단가를 변경할 때 함께 갱신하는 버전.
        @Nullable BigDecimal inputPrice,      // 일반 입력 100만 토큰당 USD.
        @Nullable BigDecimal outputPrice,     // 출력 100만 토큰당 USD. 추론 토큰을 이미 포함한다.
        @Nullable BigDecimal cachedInputPrice // 캐시 입력 100만 토큰당 USD.
) { }
