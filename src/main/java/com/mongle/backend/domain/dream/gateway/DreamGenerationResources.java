package com.mongle.backend.domain.dream.gateway;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** 외부 응답 대기에는 스레드를 쓰지 않고, 검증·JPA 저장만 제한된 전용 풀에서 실행한다. */
public final class DreamGenerationResources implements AutoCloseable {
    private final int capacity;
    private final Semaphore slots;
    private final ThreadPoolExecutor executor;
    private final Object lifecycle = new Object();
    private boolean closing;

    public DreamGenerationResources(int capacity) {
        if (capacity < 1 || capacity > 64) {
            throw new IllegalArgumentException("꿈 AI 동시 처리 제한은 1~64이어야 합니다.");
        }
        this.capacity = capacity;
        slots = new Semaphore(capacity);
        executor =
                new ThreadPoolExecutor(
                        2,
                        2,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(capacity),
                        Thread.ofPlatform().name("dream-result-", 0).factory(),
                        new ThreadPoolExecutor.AbortPolicy());
    }

    public <T> CompletableFuture<T> execute(
            Supplier<CompletableFuture<String>> generation,
            BiFunction<String, Throwable, T> completion) {
        synchronized (lifecycle) {
            if (closing || !slots.tryAcquire()) {
                throw new RejectedExecutionException("꿈 AI 동시 처리 한도 초과 또는 종료 중입니다.");
            }
        }
        CompletableFuture<String> source;
        try {
            source = java.util.Objects.requireNonNull(generation.get());
        } catch (RuntimeException ex) {
            source = CompletableFuture.failedFuture(ex);
        }
        // 한 작업이 제출하는 후속 작업은 하나이고 슬롯은 저장 종료까지 유지한다.
        // 큐 용량을 슬롯 수와 같게 두어 진행 중 호출의 완료 작업을 수용한다.
        var result = source.handleAsync(completion, executor);
        var released =
                result.whenComplete(
                        (value, failure) -> {
                            synchronized (lifecycle) {
                                slots.release();
                                lifecycle.notifyAll();
                            }
                        });
        // HTTP 연결 종료로 반환 Future가 취소되어도 저장과 슬롯 반납은 계속한다.
        return released.copy();
    }

    @Override
    public void close() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        synchronized (lifecycle) {
            closing = true;
            try {
                while (slots.availablePermits() < capacity) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) break;
                    TimeUnit.NANOSECONDS.timedWait(lifecycle, remaining);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        executor.shutdown();
        try {
            long remaining = Math.max(0, deadline - System.nanoTime());
            if (!executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
