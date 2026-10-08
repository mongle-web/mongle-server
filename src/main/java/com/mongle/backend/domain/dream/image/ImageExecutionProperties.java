package com.mongle.backend.domain.dream.image;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 호출 대기와 파일 처리까지 포함한 인스턴스별 이미지 작업 한도. */
@ConfigurationProperties("mongle.image.execution")
public record ImageExecutionProperties(@DefaultValue("2") int maxConcurrentCalls) {
    public ImageExecutionProperties {
        if (maxConcurrentCalls < 1 || maxConcurrentCalls > 16) {
            throw new IllegalArgumentException("이미지 동시 처리 제한은 1~16이어야 합니다.");
        }
    }
}
