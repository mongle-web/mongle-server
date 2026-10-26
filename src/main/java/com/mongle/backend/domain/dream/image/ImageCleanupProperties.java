package com.mongle.backend.domain.dream.image;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

@ConfigurationProperties("mongle.image.cleanup")
public record ImageCleanupProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("2s") Duration pollInterval,
        @DefaultValue("20") int batchSize,
        @DefaultValue("2") int maxConcurrentCalls,
        @DefaultValue("2m") Duration lease,
        @DefaultValue("30s") Duration retryBaseDelay,
        @DefaultValue("1h") Duration retryMaxDelay,
        @DefaultValue("5") int maxAttempts) {
    public ImageCleanupProperties {
        requireRange(pollInterval, Duration.ofMillis(100), Duration.ofMinutes(1));
        requireRange(lease, Duration.ofSeconds(5), Duration.ofMinutes(30));
        requireRange(retryBaseDelay, Duration.ofMillis(100), Duration.ofHours(24));
        requireRange(retryMaxDelay, retryBaseDelay, Duration.ofHours(24));
        if (batchSize < 1 || batchSize > 100 || maxConcurrentCalls < 1 || maxConcurrentCalls > 16
                || maxAttempts < 1 || maxAttempts > 100)
            throw new IllegalArgumentException("이미지 정리 실행 설정이 허용 범위를 벗어났습니다.");
    }

    private static void requireRange(Duration value, Duration min, Duration max) {
        if (value == null || min == null || value.compareTo(min) < 0 || value.compareTo(max) > 0)
            throw new IllegalArgumentException("이미지 정리 시간 설정이 허용 범위를 벗어났습니다.");
    }

    public Duration retryDelay(int attempt) {
        long multiplier = 1L << Math.min(30, Math.max(0, attempt - 1));
        return Duration.ofMillis(Math.min(retryMaxDelay.toMillis(), retryBaseDelay.toMillis() * multiplier));
    }
}
