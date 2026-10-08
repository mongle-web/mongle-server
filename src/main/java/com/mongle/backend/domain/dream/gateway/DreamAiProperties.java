package com.mongle.backend.domain.dream.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("mongle.dream.ai")
public record DreamAiProperties(@DefaultValue("4") int maxConcurrentCalls) {

    public DreamAiProperties {
        if (maxConcurrentCalls < 1 || maxConcurrentCalls > 64) {
            throw new IllegalArgumentException("꿈 AI 동시 호출 제한은 1~64이어야 합니다.");
        }
    }
}
