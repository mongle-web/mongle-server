package com.mongle.backend.domain.dream.image;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** 외부 대기는 슬롯만 점유하고 검증·업로드·DB 기록은 제한된 전용 풀에서 수행한다. */
public final class ImageGenerationResources implements AutoCloseable {
    private final int capacity;
    private final Semaphore slots;
    private final ThreadPoolExecutor executor;
    private final Object lifecycle = new Object();
    private boolean closing;

    public ImageGenerationResources(int capacity) {
        this(capacity, 2);
    }

    public ImageGenerationResources(int capacity, int resultThreads) {
        if (capacity < 1 || capacity > 64) {
            throw new IllegalArgumentException("이미지 동시 처리 제한은 1~64이어야 합니다.");
        }
        if (resultThreads < 1 || resultThreads > 8) {
            throw new IllegalArgumentException("이미지 결과 처리 스레드는 1~8이어야 합니다.");
        }
        this.capacity = capacity;
        slots = new Semaphore(capacity);
        executor = new ThreadPoolExecutor(
                Math.min(resultThreads, capacity), Math.min(resultThreads, capacity),
                0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity),
                Thread.ofPlatform().name("image-result-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** 사용자 잠금 안에서도 기다리지 않는다. 재사용 요청에는 호출하지 않는다. */
    public Permit reserve() {
        synchronized (lifecycle) {
            if (closing || !slots.tryAcquire()) {
                throw new RejectedExecutionException("이미지 처리 한도 초과 또는 종료 중입니다.");
            }
            return new Permit();
        }
    }

    public final class Permit implements AutoCloseable {
        private final AtomicBoolean released = new AtomicBoolean();
        private final AtomicBoolean started = new AtomicBoolean();

        public <T> CompletableFuture<T> execute(
                Supplier<CompletableFuture<byte[]>> generation,
                BiFunction<byte[], Throwable, T> completion) {
            if (released.get() || !started.compareAndSet(false, true)) {
                throw new IllegalStateException("이미지 실행 슬롯은 한 번만 사용할 수 있습니다.");
            }
            CompletableFuture<byte[]> source;
            try {
                source = Objects.requireNonNull(generation.get());
            } catch (RuntimeException ex) {
                source = CompletableFuture.failedFuture(ex);
            }
            // 슬롯당 완료 작업 하나만 제출하므로 큐가 진행 중 작업 전체를 수용한다.
            var result = source.handleAsync(completion, executor);
            var finished = result.whenComplete((value, failure) -> close());
            // HTTP 종료/취소가 내부 저장 및 슬롯 반납을 취소하지 않게 분리한다.
            return finished.copy();
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                synchronized (lifecycle) {
                    slots.release();
                    lifecycle.notifyAll();
                }
            }
        }
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
            if (!executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) executor.shutdownNow();
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
