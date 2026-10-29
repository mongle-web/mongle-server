package com.mongle.backend.domain.world.summary;

import com.mongle.backend.domain.dream.gateway.DreamGenerationResources;

import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public final class WorldSummaryWorker implements AutoCloseable {
    private final WorldSummaryTransactions transactions;
    private final WorldSummaryGenerator generator;
    private final DreamGenerationResources resources;
    private final WorldSummaryProperties properties;
    private final AtomicInteger active = new AtomicInteger();
    private final ScheduledThreadPoolExecutor scheduler =
            new ScheduledThreadPoolExecutor(
                    1, Thread.ofPlatform().name("world-summary-", 0).factory());
    private boolean started, closing;

    public WorldSummaryWorker(
            WorldSummaryTransactions transactions,
            WorldSummaryGenerator generator,
            DreamGenerationResources resources,
            WorldSummaryProperties properties) {
        this.transactions = transactions;
        this.generator = generator;
        this.resources = resources;
        this.properties = properties;
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (started || closing) return;
        started = true;
        if (properties.enabled() && properties.workerEnabled())
            scheduler.scheduleWithFixedDelay(
                    this::poll, 0, properties.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    public synchronized void poll() {
        if (closing || !properties.enabled()) return;
        try {
            for (var candidate : transactions.runnable()) {
                if (active.get() >= properties.maxConcurrentCalls()) break;
                WorldSummaryTransactions.Step step;
                try {
                    step = transactions.claim(candidate.getUserId(), candidate.getWorldId());
                } catch (RuntimeException ex) {
                    log.warn("World summary claim was not persisted; retrying next poll.");
                    continue;
                }
                if (step == null) continue;
                active.incrementAndGet();
                try {
                    resources
                            .execute(
                                    () ->
                                            generator.generate(
                                                    new WorldSummaryGenerator.Input(
                                                            step.userId(), step.data())),
                                    (output, failure) -> {
                                        transactions.complete(step, output, failure);
                                        return null;
                                    })
                            .whenComplete(
                                    (ignored, failure) -> {
                                        if (failure != null)
                                            log.warn(
                                                    "World summary completion was not persisted;"
                                                        + " lease recovery is required.");
                                        active.decrementAndGet();
                                    });
                } catch (RuntimeException ex) {
                    try {
                        transactions.complete(step, null, ex);
                    } catch (RuntimeException ignored) {
                        log.warn(
                                "World summary rejection was not persisted; lease recovery is"
                                    + " required.");
                    } finally {
                        active.decrementAndGet();
                    }
                }
            }
        } catch (RuntimeException ex) {
            log.warn("World summary poll failed; retrying next poll.");
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
