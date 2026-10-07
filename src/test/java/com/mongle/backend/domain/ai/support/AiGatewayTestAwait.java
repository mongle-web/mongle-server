package com.mongle.backend.domain.ai.support;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** 테스트 단언에서만 제한 시간으로 기다린다. 운영 호출부는 완료 처리를 연결해야 한다. */
public final class AiGatewayTestAwait {
    private AiGatewayTestAwait() { }

    public static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException known) throw known;
            if (exception.getCause() instanceof Error error) throw error;
            throw new AssertionError("비동기 처리에 예상하지 못한 예외가 발생했습니다.", exception.getCause());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("비동기 테스트 대기가 중단됐습니다.", exception);
        } catch (java.util.concurrent.TimeoutException exception) {
            throw new AssertionError("비동기 테스트가 제한 시간 안에 완료되지 않았습니다.", exception);
        }
    }
}
