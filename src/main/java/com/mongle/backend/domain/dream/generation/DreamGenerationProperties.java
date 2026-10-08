package com.mongle.backend.domain.dream.generation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("mongle.dream.generation")
public record DreamGenerationProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("2s") Duration pollInterval) {
    public DreamGenerationProperties {
        if (pollInterval == null
                || pollInterval.compareTo(Duration.ofMillis(100)) < 0
                || pollInterval.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("자동 생성 조회 주기는 100ms~1분이어야 합니다.");
        }
    }
}
