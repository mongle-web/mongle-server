package com.mongle.backend.domain.world.composition;

import com.mongle.backend.domain.world.bridge.*;
import com.mongle.backend.global.common.GenerationStatus;

import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public final class WorldCompositionWorker implements AutoCloseable {
    private final WorldCompositionTransactions transactions;
    private final BridgeService bridges;
    private final WorldCompositionProperties properties;
    private final ScheduledThreadPoolExecutor scheduler =
            new ScheduledThreadPoolExecutor(
                    1, Thread.ofPlatform().name("world-composition-", 0).factory());
    private final AtomicInteger active = new AtomicInteger();
    private boolean started, closing;

    public WorldCompositionWorker(
            WorldCompositionTransactions transactions,
            BridgeService bridges,
            WorldCompositionProperties properties) {
        this.transactions = transactions;
        this.bridges = bridges;
        this.properties = properties;
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (started || closing) return;
        started = true;
        if (properties.workerEnabled())
            scheduler.scheduleWithFixedDelay(
                    this::poll, 0, properties.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    public synchronized void poll() {
        if (closing) return;
        try {
            for (var candidate : transactions.runnable()) {
                if (active.get() >= properties.maxConcurrentCalls()) break;
                WorldCompositionTransactions.Step step;
                try {
                    step = transactions.next(candidate.getUserId(), candidate.getWorldId());
                } catch (RuntimeException ex) {
                    log.warn("World composition claim failed; retrying next poll.");
                    continue;
                }
                if (step == null) continue;
                active.incrementAndGet();
                CompletableFuture<BridgeResponse> result;
                try {
                    result =
                            step.bridgeId() == null
                                    ? bridges.generate(
                                            step.userId(),
                                            new BridgeRequest(step.before(), step.after()))
                                    : CompletableFuture.completedFuture(
                                            bridges.get(step.userId(), step.bridgeId()));
                    if (step.retrying())
                        result =
                                result.thenCompose(
                                        b ->
                                                b.status() == GenerationStatus.FAILED
                                                        ? bridges.retry(
                                                                step.userId(),
                                                                b.bridgeId(),
                                                                b.jobVersion())
                                                        : CompletableFuture.completedFuture(b));
                } catch (RuntimeException ex) {
                    result = CompletableFuture.failedFuture(ex);
                }
                result.whenComplete(
                        (response, failure) -> {
                            try {
                                transactions.completed(step, response, failure != null);
                            } catch (RuntimeException ex) {
                                log.warn(
                                        "World composition completion was not persisted; lease"
                                                + " recovery is required.");
                            } finally {
                                active.decrementAndGet();
                            }
                        });
            }
        } catch (RuntimeException ex) {
            log.warn("World composition poll failed; retrying next poll.");
        }
    }

    public int activeCount() {
        return active.get();
    }

    @Override
    public synchronized void close() {
        closing = true;
        scheduler.shutdownNow();
    }
}
