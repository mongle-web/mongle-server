package com.mongle.backend.domain.world.summary;

import org.springframework.boot.context.properties.*;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("mongle.world.summary")
public record WorldSummaryProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("true") boolean workerEnabled,
        @DefaultValue("2s") Duration pollInterval,
        @DefaultValue("20") int batchSize,
        @DefaultValue("2") int maxConcurrentCalls,
        @DefaultValue("120000") int maxInputCharacters) {
    public WorldSummaryProperties {
        if (pollInterval == null
                || pollInterval.compareTo(Duration.ofSeconds(1)) < 0
                || pollInterval.compareTo(Duration.ofMinutes(1)) > 0
                || batchSize < 1
                || batchSize > 100
                || maxConcurrentCalls < 1
                || maxConcurrentCalls > 8
                || maxInputCharacters < 1000
                || maxInputCharacters > 120000)
            throw new IllegalArgumentException("세계관 결과 생성 제한을 확인해주세요.");
    }
}
