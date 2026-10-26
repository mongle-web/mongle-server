package com.mongle.backend.domain.dream.image;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import java.util.concurrent.*;

/** 새 생성의 활성 여부와 무관하게 기존 예약의 복구를 계속한다. */
@Slf4j
public final class ImageRecoveryWorker implements AutoCloseable {
    private final ImageRecovery recovery;
    private final ImageRecoveryProperties properties;
    private final ScheduledThreadPoolExecutor execution = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().name("image-credit-recovery-", 0).factory());
    private boolean started;
    public ImageRecoveryWorker(ImageRecovery recovery, ImageRecoveryProperties properties) {
        this.recovery = recovery; this.properties = properties;
        execution.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        execution.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (started || execution.isShutdown()) return;
        started = true;
        if (properties.enabled()) execution.scheduleWithFixedDelay(this::poll, 0,
                properties.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
    }
    void poll() {
        try { recovery.recoverExpired(); }
        catch (RuntimeException ex) { log.warn("이미지 크레딧 복구를 완료하지 못했습니다. 다음 주기에 다시 확인합니다."); }
    }
    @Override public synchronized void close() { execution.shutdownNow(); }
}
