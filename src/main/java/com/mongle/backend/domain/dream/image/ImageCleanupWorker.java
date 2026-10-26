package com.mongle.backend.domain.dream.image;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 예약/저장은 짧은 트랜잭션, 외부 삭제는 제한된 별도 실행기에서 처리한다. */
@Slf4j
public final class ImageCleanupWorker implements AutoCloseable {
    private final ImageCleanupWorkTransactions transactions;
    private final ImageAssetStore storage;
    private final ImageCleanupProperties properties;
    private final ScheduledThreadPoolExecutor control = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().name("image-cleanup-control-", 0).factory());
    private final ThreadPoolExecutor execution;
    private final AtomicInteger active = new AtomicInteger();
    private volatile boolean closing;

    public ImageCleanupWorker(ImageCleanupWorkTransactions transactions, ImageAssetStore storage,
            ImageCleanupProperties properties) {
        this.transactions = transactions;
        this.storage = storage;
        this.properties = properties;
        int size = properties.maxConcurrentCalls();
        execution = new ThreadPoolExecutor(size, size, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(size), Thread.ofPlatform().name("image-cleanup-io-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        control.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        control.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (properties.enabled())
            control.scheduleWithFixedDelay(this::poll, 0, properties.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    public synchronized void poll() {
        if (closing || !properties.enabled() || active.get() >= properties.maxConcurrentCalls()) return;
        try {
            if (!storage.available()) return;
            var candidates = transactions.candidates(properties.batchSize());
            for (var id : candidates) {
                if (closing || active.get() >= properties.maxConcurrentCalls()) break;
                var claim = transactions.claim(id);
                if (claim.isEmpty()) continue;
                active.incrementAndGet();
                try {
                    execution.execute(() -> {
                        try { process(claim.get()); }
                        finally { active.decrementAndGet(); }
                    });
                } catch (RejectedExecutionException ex) {
                    active.decrementAndGet();
                    transactions.postpone(claim.get());
                }
            }
        } catch (RuntimeException ex) {
            log.warn("이미지 정리 예약을 완료하지 못했습니다. 다음 주기에 다시 확인합니다.");
        }
    }

    private void process(ImageCleanupWorkTransactions.Claim claim) {
        try {
            if (!storage.available()) { transactions.postpone(claim); return; }
            if (!transactions.prepare(claim)) return;
            try { storage.delete(claim.key()); }
            catch (RuntimeException ex) { transactions.failed(claim); return; }
            transactions.complete(claim);
        } catch (RuntimeException ex) {
            // 외부 키/오류 본문 없이 경고한다. 저장 실패는 lease 만료 후 멱등 삭제로 복구한다.
            log.warn("이미지 정리 상태를 확정하지 못했습니다. 만료 후 다시 확인합니다.");
        }
    }

    public int activeCount() { return active.get(); }

    @Override public void close() {
        synchronized (this) { closing = true; control.shutdown(); execution.shutdown(); }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            if (!control.awaitTermination(5, TimeUnit.SECONDS)) control.shutdownNow();
            if (!execution.awaitTermination(Math.max(0, deadline-System.nanoTime()), TimeUnit.NANOSECONDS))
                execution.shutdownNow();
        } catch (InterruptedException ex) {
            control.shutdownNow(); execution.shutdownNow(); Thread.currentThread().interrupt();
        }
    }
}
