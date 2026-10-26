package com.mongle.backend.domain.dream.image;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 호출 대기와 파일 처리까지 포함한 인스턴스별 이미지 작업 한도. */
@ConfigurationProperties("mongle.image.execution")
public record ImageExecutionProperties(
        @DefaultValue("16") int maxConcurrentCalls,
        @DefaultValue("2") int resultThreads) {
    public ImageExecutionProperties {
        if (maxConcurrentCalls < 1 || maxConcurrentCalls > 64) {
            throw new IllegalArgumentException("이미지 동시 처리 제한은 1~64이어야 합니다.");
        }
        if (resultThreads < 1 || resultThreads > 8) {
            throw new IllegalArgumentException("이미지 결과 처리 스레드는 1~8이어야 합니다.");
        }
    }
}
