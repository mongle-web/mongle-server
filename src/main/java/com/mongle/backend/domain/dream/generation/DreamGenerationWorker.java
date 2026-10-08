package com.mongle.backend.domain.dream.generation;

import com.mongle.backend.domain.dream.analysis.AnalysisErrorCode;
import com.mongle.backend.domain.dream.analysis.DreamStructureService;
import com.mongle.backend.domain.dream.exception.DreamErrorCode;
import com.mongle.backend.domain.dream.gateway.DreamAiProperties;
import com.mongle.backend.domain.dream.story.DreamStoryService;
import com.mongle.backend.domain.dream.story.StoryErrorCode;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.BusinessException;

import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;

import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 제어 스레드 하나는 짧은 DB 예약·완료 처리만 수행한다. AI 대기를 위한 작업을 제출하지 않는다. */
@Slf4j
public final class DreamGenerationWorker implements AutoCloseable {
    private final DreamGenerationTransactions transactions;
    private final DreamStructureService analysis;
    private final DreamStoryService story;
    private final DreamGenerationProperties properties;
    private final int capacity;
    private final Clock authClock;
    private final AtomicInteger active = new AtomicInteger();
    private final ScheduledThreadPoolExecutor control =
            new ScheduledThreadPoolExecutor(
                    1, Thread.ofPlatform().name("dream-auto-control-", 0).factory());
    private volatile boolean closing;

    public DreamGenerationWorker(
            DreamGenerationTransactions transactions,
            DreamStructureService analysis,
            DreamStoryService story,
            DreamGenerationProperties properties,
            DreamAiProperties ai,
            Clock authClock) {
        this.transactions = transactions;
        this.analysis = analysis;
        this.story = story;
        this.properties = properties;
        this.capacity = ai.maxConcurrentCalls();
        this.authClock = authClock;
        control.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        control.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (properties.enabled()) {
            control.scheduleWithFixedDelay(
                    this::poll, 0, properties.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    public synchronized void poll() {
        if (closing || active.get() >= capacity) {
            return;
        }
        try {
            for (var candidate : transactions.candidates()) {
                if (closing || active.get() >= capacity) {
                    break;
                }
                transactions.claim(candidate).ifPresent(this::dispatch);
            }
        } catch (RuntimeException ex) {
            // 원문·키·외부 오류 본문을 출력하지 않는다. DB 복구 후 다음 조회에서 재개한다.
            log.warn("자동 생성 작업 조회를 완료하지 못했습니다. 다음 주기에 다시 확인합니다.");
        }
    }

    private void dispatch(DreamGenerationTransactions.Claim claim) {
        active.incrementAndGet();
        try {
            var progress = transactions.progress(claim);
            if (progress != null && progress.status() == GenerationStatus.COMPLETED) {
                transactions.result(claim, GenerationStatus.COMPLETED, null);
                active.decrementAndGet();
                return;
            }
            if (progress != null && progress.status() == GenerationStatus.PROCESSING) {
                boolean live = progress.leaseUntil() != null
                        && progress.leaseUntil().isAfter(authClock.instant());
                transactions.result(
                        claim, live ? GenerationStatus.PROCESSING : GenerationStatus.FAILED,
                        live ? null : "RECOVERY_REQUIRED");
                active.decrementAndGet();
                return;
            }
            if (progress != null
                    && (claim.recovering() || "SOURCE_CHANGED".equals(progress.failureCode()))) {
                transactions.result(claim, GenerationStatus.FAILED, progress.failureCode());
                active.decrementAndGet();
                return;
            }
            CompletableFuture<Outcome> future =
                    claim.stage() == DreamGenerationJob.Stage.ANALYSIS
                            ? analysis.analyzeSource(
                                            claim.userId(), claim.dreamId(), claim.sourceRevision())
                                    .thenApply(result -> new Outcome(result.status(), result.failureCode()))
                            : story.generateSource(
                                            claim.userId(), claim.dreamId(), claim.sourceRevision())
                                    .thenApply(result -> new Outcome(result.status(), result.failureCode()));
            // DB 기록은 ai-log/ai-response 또는 공용 ForkJoinPool에서 실행하지 않는다.
            future.whenCompleteAsync(
                    (outcome, failure) -> {
                        try {
                            finish(claim, outcome, failure);
                        } finally {
                            active.decrementAndGet();
                        }
                    },
                    control);
        } catch (RuntimeException ex) {
            try {
                finish(claim, null, ex);
            } finally {
                active.decrementAndGet();
            }
        }
    }

    private record Outcome(GenerationStatus status, String code) {}

    private void finish(DreamGenerationTransactions.Claim claim, Outcome outcome, Throwable failure) {
        try {
            if (failure == null) {
                transactions.result(claim, outcome.status(), outcome.code());
                return;
            }
            while (failure instanceof CompletionException && failure.getCause() != null) {
                failure = failure.getCause();
            }
            // DB 장애는 호출 결과를 확정하지 않는다. lease 만료 후 저장된 결과로 복구한다.
            if (failure instanceof DataAccessException) {
                return;
            }
            String code = "CALL_FAILED";
            if (failure instanceof BusinessException business) {
                var error = business.getErrorCode();
                if (error == AnalysisErrorCode.UNAVAILABLE || error == StoryErrorCode.UNAVAILABLE) {
                    code = "UNAVAILABLE";
                } else if (error == DreamErrorCode.NOT_FOUND) {
                    code = "SOURCE_DELETED";
                } else if (error == DreamErrorCode.VERSION_CONFLICT
                        || error == StoryErrorCode.ANALYSIS_STALE) {
                    code = "SOURCE_CHANGED";
                }
            }
            transactions.result(claim, GenerationStatus.FAILED, code);
        } catch (RuntimeException ex) {
            log.warn("자동 생성 진행 상태를기록하지 못했습니다. 만료 후 저장된 결과를 확인합니다.");
        }
    }

    @Override
    public void close() {
        closing = true;
        control.shutdown();
        try {
            if (!control.awaitTermination(5, TimeUnit.SECONDS)) {
                control.shutdownNow();
            }
        } catch (InterruptedException ex) {
            control.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
