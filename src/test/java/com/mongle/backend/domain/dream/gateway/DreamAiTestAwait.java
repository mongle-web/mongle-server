package com.mongle.backend.domain.dream.gateway;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** 테스트에서만 결과를 기다린다. 비동기 예외도 기존 도메인 예외 검증을 유지한다. */
public final class DreamAiTestAwait {
    private DreamAiTestAwait() {}

    public static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException failure) throw failure;
            if (ex.getCause() instanceof Error failure) throw failure;
            throw new AssertionError(ex.getCause());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        } catch (java.util.concurrent.TimeoutException ex) {
            throw new AssertionError("꿈 AI 테스트 결과 대기 시간 초과", ex);
        }
    }
}
