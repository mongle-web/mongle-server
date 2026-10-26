package com.mongle.backend.domain.dream.image;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

@ConfigurationProperties("mongle.image.recovery")
public record ImageRecoveryProperties(@DefaultValue("true") boolean enabled,
        @DefaultValue("30s") Duration pollInterval, @DefaultValue("20") int batchSize) {
    public ImageRecoveryProperties {
        if (pollInterval == null || pollInterval.compareTo(Duration.ofSeconds(1)) < 0
                || pollInterval.compareTo(Duration.ofMinutes(5)) > 0 || batchSize < 1 || batchSize > 100)
            throw new IllegalArgumentException("이미지 예약 복구 설정이 허용 범위를 벗어났습니다.");
    }
}
