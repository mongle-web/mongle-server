package com.mongle.backend.domain.ai.config;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * AI 처리에 필요한 실행 자원을 한곳에서 생성·종료한다. 공용 ForkJoinPool에 DB 작업을 보내지 않는다.
 * HTTP 응답 대기에는 이 작업 스레드들이 사용되지 않는다. 타이머는 시간이 됐을 때 짧은 작업만 실행한다.
 */
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

    public int maxConcurrentCalls() {
        return properties.maxConcurrentCalls();
    }

    public void executeResponse(Runnable task) {
        responses.execute(task);
    }

    public void executeLog(Runnable task) {
        logs.execute(task);
    }

    public ScheduledFuture<?> schedule(Runnable task, Duration delay) {
        // sleep으로 스레드를 점유하지 않고 지정된 시간이 지난 뒤 실행 가능하도록 예약한다.
        return timer.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);
    }

    @Override
    public void close() {
        // Gateway가 먼저 진행 중인 결과를 종료한다. 이후 의존 자원인 이 Bean의 작업 풀을 정리한다.
        timer.shutdownNow();
        responses.shutdownNow();
        logs.shutdownNow();
    }
}
