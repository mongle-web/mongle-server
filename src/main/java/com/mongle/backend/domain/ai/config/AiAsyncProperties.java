package com.mongle.backend.domain.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 비동기라고 작업을 무제한 쌓지 않는다. 인스턴스별 호출 수와 실행 큐 크기를 제한한다. */
@ConfigurationProperties("mongle.ai.async")
public record AiAsyncProperties(
        @DefaultValue("16") int maxConcurrentCalls,       // 재시도·로그 저장을 포함한 논리 호출 수의 상한.
        @DefaultValue("2") int responseThreads,            // 요청 준비·응답 변환을 실행할 작업 스레드 수.
        @DefaultValue("64") int responseQueueCapacity,     // 응답 처리 대기열의 상한. AI 요청 대기열이 아니다.
        @DefaultValue("2") int logThreads,                 // JPA 로그 저장만 담당할 스레드 수.
        @DefaultValue("64") int logQueueCapacity           // 저장 작업 대기열의 상한. 가득 차면 로그를 생략한다.
) {
    public AiAsyncProperties {
        // 과도한 설정이나 잘못된 값은 서버 시작 시 발견한다. 기본값은 초기 운영값이며 부하 측정으로 조정한다.
        range(maxConcurrentCalls, 1, 1000, "AI 동시 호출 수");
        range(responseThreads, 1, 32, "AI 응답 처리 스레드 수");
        range(logThreads, 1, 32, "AI 로그 저장 스레드 수");
        range(responseQueueCapacity, 1, 10000, "AI 응답 처리 큐 크기");
        range(logQueueCapacity, 1, 10000, "AI 로그 저장 큐 크기");
    }

    private static void range(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + "는 " + min + " 이상 " + max + " 이하여야 합니다.");
        }
    }
}
