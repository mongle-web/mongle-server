package com.mongle.backend.domain.ai.config;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * AI 처리에 필요한 실행 자원을 한곳에서 생성·종료한다. 공용 ForkJoinPool에 DB 작업을 보내지 않는다.
 * HTTP 응답 대기에는 이 작업 스레드들이 사용되지 않는다. 타이머는 시간이 됐을 때 짧은 작업만 실행한다.
 */
@Slf4j
public final class AiAsyncResources implements AutoCloseable {
    private final AiAsyncProperties properties;
    private final ThreadPoolExecutor responses; // JSON 변환과 다음 HTTP 시도 준비를 담당한다.
    private final ThreadPoolExecutor logs;      // 블로킹 JPA 저장을 응답 처리와 분리한다.
    private final ScheduledThreadPoolExecutor timer; // 시간 초과와 재시도를 예약한다.

    public AiAsyncResources(AiAsyncProperties properties) {
        this.properties = properties;
        responses = pool(properties.responseThreads(), properties.responseQueueCapacity(), "ai-response-");
        logs = pool(properties.logThreads(), properties.logQueueCapacity(), "ai-log-");
        timer = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().name("ai-timer-", 0).factory());
        // 취소한 45초 타이머가 만료될 때까지 큐에 남지 않도록 즉시 제거한다.
        timer.setRemoveOnCancelPolicy(true);
        timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    private static ThreadPoolExecutor pool(int threads, int capacity, String prefix) {
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), Thread.ofPlatform().name(prefix, 0).factory(),
                // 포화 시 호출 스레드에서 DB 작업을 대신 실행하지 않는다. 제출 거절을 호출부가 처리한다.
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** 재시도·로그 저장을 포함해 동시에 진행할 수 있는 논리 호출 수를 반환한다. */
    public int maxConcurrentCalls() {
        return properties.maxConcurrentCalls();
    }

    /** 응답 변환 작업을 제출한다. 포화·종료 상태에서는 제출 거절을 호출부에서 처리한다. */
    public void executeResponse(Runnable task) {
        responses.execute(task);
    }

    /** DB 저장 작업을 전용 풀에 제출한다. 호출 스레드에서 대신 실행하지 않는다. */
    public void executeLog(Runnable task) {
        logs.execute(task);
    }

    /** 응답 대기 스레드를 점유하지 않고 시간 초과·재시도 작업을 예약한다. */
    public ScheduledFuture<?> schedule(Runnable task, Duration delay) {
        // sleep으로 스레드를 점유하지 않고 지정된 시간이 지난 뒤 실행 가능하도록 예약한다.
        return timer.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);
    }

    /**
     * 새 로그 제출을 막고 이미 제출된 작업을 제한 시간 동안 마친다.
     * Gateway가 호출 정리와 마지막 로그 제출을 끝낸 뒤, DB 자원이 닫히기 전에 호출해야 한다.
     * 강제 종료는 인터럽트를 요청할 뿐 DB 드라이버나 작업의 즉시 중단을 보장하지 않는다.
     *
     * @param timeout 기다릴 최대 시간. 0이면 즉시 종료 여부를 확인하며 음수는 허용하지 않는다.
     * @return 모든 저장 작업이 끝났으면 true, 시간 초과나 종료 스레드 인터럽트이면 false
     */
    public boolean drainLogs(Duration timeout) {
        Objects.requireNonNull(timeout, "로그 종료 대기 시간은 필수입니다.");
        if (timeout.isNegative()) throw new IllegalArgumentException("로그 종료 대기 시간은 음수일 수 없습니다.");
        long nanos = timeout.toNanos();
        logs.shutdown(); // 제출된 작업은 계속 실행하며 새로운 작업만 거절한다.
        try {
            if (logs.awaitTermination(nanos, TimeUnit.NANOSECONDS)) {
                log.info("AI 로그 저장 종료 완료");
                return true;
            }
            // 정상 종료에서 기다릴 시간을 제한한다. DB 장애로 배포가 무한정 멈추지 않게 한다.
            int discarded = logs.shutdownNow().size();
            log.warn("AI 로그 저장 종료 시간 초과: timeoutMs={}, discardedTasks={}", timeout.toMillis(), discarded);
        } catch (InterruptedException exception) {
            int discarded = logs.shutdownNow().size();
            Thread.currentThread().interrupt(); // 상위 종료 처리에서도 인터럽트를 관측할 수 있게 복구한다.
            log.warn("AI 로그 저장 종료 대기 중단: discardedTasks={}", discarded);
        }
        return false;
    }

    /** Gateway에서 로그 배출을 마친 뒤 실행 자원을 최종 정리한다. 미종료 작업에는 인터럽트를 요청한다. */
    @Override
    public void close() {
        // Gateway가 먼저 진행 중인 결과를 종료한다. 이후 의존 자원인 이 Bean의 작업 풀을 정리한다.
        timer.shutdownNow();
        responses.shutdownNow();
        logs.shutdownNow();
    }
}
