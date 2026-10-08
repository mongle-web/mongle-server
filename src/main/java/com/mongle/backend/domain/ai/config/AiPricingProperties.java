package com.mongle.backend.domain.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/** 공개된 단가를 모델별로 등록한다. 알 수 없는 모델을 비슷한 모델의 가격으로 대체하지 않는다. */
@ConfigurationProperties("mongle.ai.pricing")
public record AiPricingProperties(Map<String, ModelPrice> models) {
    public AiPricingProperties {
        // 설정이 없으면 빈 가격표를 사용한다. 호출은 가능하지만 비용은 PRICE_MISSING으로 남는다.
        models = models == null ? Map.of() : Map.copyOf(models);
    }

    public record ModelPrice(String version, BigDecimal inputUsdPerMillion,
                             BigDecimal outputUsdPerMillion, BigDecimal cachedInputUsdPerMillion) {
        public ModelPrice {
            // 잘못된 가격 설정은 서버 시작 시 발견한다. 실행 중 조용히 0으로 계산하지 않는다.
            Objects.requireNonNull(version, "단가 버전은 필수입니다.");
            if (version.isBlank() || version.length() > 100) {
                throw new IllegalArgumentException("단가 버전은 공백이 아니며 100자 이하여야 합니다.");
            }
            validatePrice(inputUsdPerMillion);
            validatePrice(outputUsdPerMillion);
            validatePrice(cachedInputUsdPerMillion);
        }

        private static void validatePrice(BigDecimal price) {
            Objects.requireNonNull(price, "토큰 단가는 필수입니다.");
            // 로그의 DECIMAL(12, 8)에 반올림 없이 보관할 수 있는 범위만 허용한다.
            if (price.signum() < 0 || price.compareTo(new BigDecimal("10000")) >= 0
                    || price.stripTrailingZeros().scale() > 8) {
                throw new IllegalArgumentException("토큰 단가는 0 이상 10000 미만이며 소수점 8자리 이하여야 합니다.");
            }
        }
    }
}
