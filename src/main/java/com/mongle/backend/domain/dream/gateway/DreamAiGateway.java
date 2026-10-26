package com.mongle.backend.domain.dream.gateway;

import com.mongle.backend.domain.ai.dto.request.AiGenerationRequest;
import com.mongle.backend.domain.ai.error.AiGatewayErrorCode;
import com.mongle.backend.domain.ai.error.AiGatewayException;
import com.mongle.backend.domain.ai.gateway.AiGateway;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

/** 분석·서사화·꿈 사이 연결이 공유하는 호출 제한. 슬롯을 기다리는 작업이나 별도 작업 풀을 만들지 않는다. */
public final class DreamAiGateway {

    private final AiGateway gateway;
    private final boolean configured;
    private final Semaphore slots;

    public DreamAiGateway(AiGateway gateway, boolean configured, int maxConcurrentCalls) {
        this.gateway = Objects.requireNonNull(gateway);
        if (maxConcurrentCalls < 1 || maxConcurrentCalls > 64) {
            throw new IllegalArgumentException("꿈 AI 동시 호출 제한은 1~64이어야 합니다.");
        }
        this.configured = configured;
        this.slots = new Semaphore(maxConcurrentCalls);
    }

    public boolean available() {
        return configured;
    }

    public CompletableFuture<String> generate(AiGenerationRequest request) {
        if (!configured) {
            throw new AiGatewayException(
                    AiGatewayErrorCode.AUTHENTICATION_FAILED, null, false, null, null);
        }
        if (!slots.tryAcquire()) {
            throw new AiGatewayException(AiGatewayErrorCode.RATE_LIMITED, null, true, null, null);
        }
        try {
            // 재시도와 통신 대기를 포함한 Gateway 호출 전체에 슬롯 한 개를 사용한다.
            var call = gateway.generate(request);
            // 완료 후에만 슬롯을 반납한다. Future 반환 시점에는 아직 호출이 진행 중이다.
            var released = call.whenComplete((result, failure) -> slots.release());
            return released.thenApply(result -> result.content());
        } catch (RuntimeException ex) {
            slots.release();
            throw ex;
        }
    }
}
