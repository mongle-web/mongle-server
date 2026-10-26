package com.mongle.backend.domain.world.composition;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;
@ConfigurationProperties("mongle.world.composition")
public record WorldCompositionProperties(@DefaultValue("true") boolean enabled,@DefaultValue("true") boolean workerEnabled,
        @DefaultValue("2s") Duration pollInterval,@DefaultValue("20") int batchSize,@DefaultValue("2") int maxConcurrentCalls) {
    public WorldCompositionProperties {
        if(pollInterval==null||pollInterval.compareTo(Duration.ofMillis(100))<0||pollInterval.compareTo(Duration.ofMinutes(1))>0
                ||batchSize<1||batchSize>100||maxConcurrentCalls<1||maxConcurrentCalls>8) throw new IllegalArgumentException("세계관 작업 실행 설정이 허용 범위를 벗어났습니다.");
    }
}
