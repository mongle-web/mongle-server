package com.mongle.backend.domain.ai.liner;

import com.mongle.backend.domain.ai.config.AiAsyncResources;
import com.mongle.backend.domain.ai.dto.logging.AiGenerationAttempt;
import com.mongle.backend.domain.ai.dto.request.AiGenerationRequest;
import com.mongle.backend.domain.ai.dto.response.AiGenerationResult;
import com.mongle.backend.domain.ai.error.AiGatewayErrorCode;
import com.mongle.backend.domain.ai.error.AiGatewayException;
import com.mongle.backend.domain.ai.gateway.AiGateway;
import com.mongle.backend.domain.ai.liner.client.LinerClient;
import com.mongle.backend.domain.ai.liner.config.LinerProperties;
import com.mongle.backend.domain.ai.liner.dto.LinerResponseMetadata;
import com.mongle.backend.domain.ai.liner.mapper.LinerPayloadMapper;
import com.mongle.backend.domain.ai.service.AiGenerationLogService;
import com.mongle.backend.global.logging.HttpRequestLoggingFilter;
import com.mongle.backend.global.logging.LogValues;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.slf4j.MDC;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 요청 준비 → 비동기 HTTP → 완료 콜백 → 응답 검증 → 비동기 로그 저장 → 결과 완료를 연결한다.
 * 프롬프트·업무 검증·꿈 결과 저장은 호출 도메인이 담당한다. 이 클래스에는 트랜잭션을 걸지 않는다.
 * 읽는 순서: generate() → Call.prepare()/startAttempt() → observe() → afterLog().
 */
@Slf4j
@Component
public class LinerAiGateway implements AiGateway {
    private final LinerProperties properties;
    private final LinerClient client;
    private final LinerPayloadMapper mapper;
    private final AiGenerationLogService logs;
    private final AiAsyncResources resources;
    // 대기 스레드나 HTTP 요청을 무제한 만들지 않는다. 한도는 재시도·로그 완료까지 논리 호출 전체에 적용한다.
    private final Semaphore slots;
    private final Set<Call> active = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closing = new AtomicBoolean();

    public LinerAiGateway(LinerProperties properties, LinerClient client, LinerPayloadMapper mapper,
                          AiGenerationLogService logs, AiAsyncResources resources) {
        this.properties = properties;
        this.client = client;
        this.mapper = mapper;
        this.logs = logs;
        this.resources = resources;
        this.slots = new Semaphore(resources.maxConcurrentCalls());
    }

    @Override
    public CompletableFuture<AiGenerationResult> generate(AiGenerationRequest request) {
        Objects.requireNonNull(request, "AI 생성 요청은 필수입니다.");
        // 처리 한도에서 거절된 호출에도 ID를 부여한다. 서버 로그와 DB 시도 로그가 같은 ID를 사용한다.
        String callId = UUID.randomUUID().toString();
        if (closing.get()) return rejected(callId, "shutdown", AiGatewayErrorCode.PROVIDER_UNAVAILABLE);
        // acquire()로 자리가 날 때까지 기다리지 않는다. 초과 요청은 HTTP 전송 없이 즉시 거절한다.
        if (!slots.tryAcquire()) return rejected(callId, "capacity", AiGatewayErrorCode.CAPACITY_EXCEEDED);
        var call = new Call(request, callId);
        active.add(call);
        log.info("AI 호출 접수: requestId={}, callId={}, userId={}, taskType={}, requestedModel={}",
                call.requestId, callId, request.userId(), request.taskType(), LogValues.safe(properties.model()));
        // 등록과 서버 종료가 겹쳐도 추적 목록에 없는 호출이 계속 실행되지 않도록 다시 검사한다.
        if (closing.get()) call.finish(null, failure(AiGatewayErrorCode.PROVIDER_UNAVAILABLE));
        else dispatch(call::prepare, () -> call.finish(null, failure(AiGatewayErrorCode.CAPACITY_EXCEEDED)));
        return call.result;
    }

    private CompletableFuture<AiGenerationResult> rejected(String callId, String reason, AiGatewayErrorCode code) {
        log.warn("AI 호출 거절: requestId={}, callId={}, reason={}, code={}, maxConcurrentCalls={}",
                LogValues.safe(MDC.get(HttpRequestLoggingFilter.REQUEST_ID)), callId, reason, code.getCode(), resources.maxConcurrentCalls());
        return CompletableFuture.failedFuture(failure(code));
    }

    /** HTTP 완료 스레드·타이머에서는 짧게 작업만 제출한다. JSON 응답 처리에는 별도 제한된 풀을 사용한다. */
    private void dispatch(Runnable task, Runnable rejected) {
        try {
            resources.executeResponse(task);
        } catch (RejectedExecutionException exception) {
            log.warn("AI 응답 처리 작업 거절: queue=response, reason=capacity_or_shutdown");
            // 큐가 찬 경우도 반드시 결과를 완료해 호출부의 Future가 영원히 남지 않도록 한다.
            rejected.run();
        }
    }

    /** 논리 요청마다 생성하는 진행 상태. 사용자 원문은 이 객체에서만 유지하며 로그 DTO에는 넣지 않는다. */
    private final class Call {
        private final AiGenerationRequest request;
        private final String callId;
        // MDC는 작업 풀로 자동 전파되지 않는다. 호출 시 값을 복사해 비동기 로그에 명시적으로 전달한다.
        private final String requestId = LogValues.safe(MDC.get(HttpRequestLoggingFilter.REQUEST_ID));
        private final long started = System.nanoTime();
        private final AtomicBoolean terminal = new AtomicBoolean(); // 성공·실패·취소 중 하나만 최종 확정한다.
        private String payload;
        private @Nullable Attempt current;
        private @Nullable ScheduledFuture<?> retry;
        private final CompletableFuture<AiGenerationResult> result = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                // 파생 Future 취소만으로 원본 HTTP가 취소되지는 않는다. 여기서 통신과 재시도를 직접 정리한다.
                if (!terminal.compareAndSet(false, true)) return isCancelled();
                cleanup(failure(AiGatewayErrorCode.CANCELLED));
                logCompletion(null, failure(AiGatewayErrorCode.CANCELLED));
                return super.cancel(mayInterruptIfRunning);
            }
        };

        private Call(AiGenerationRequest request, String callId) {
            this.request = request;
            this.callId = callId;
        }

        private void prepare() {
            if (terminal.get()) return;
            if (properties.apiKey().isBlank()) {
                preflightFailure(failure(AiGatewayErrorCode.AUTHENTICATION_FAILED));
                return;
            }
            try {
                payload = mapper.requestBody(request);
            } catch (RuntimeException exception) {
                preflightFailure(failure(AiGatewayErrorCode.INVALID_REQUEST));
                return;
            }
            startAttempt(1);
        }

        private void preflightFailure(AiGatewayException error) {
            var attempt = new Attempt(1, false);
            synchronized (this) {
                if (terminal.get()) return;
                current = attempt;
                attempt.observed.set(true);
            }
            recordOutcome(attempt, null, null, error);
        }

        private void startAttempt(int number) {
            Attempt attempt;
            synchronized (this) {
                if (terminal.get()) return;
                Duration remaining = remaining();
                if (remaining.isNegative() || remaining.isZero()) {
                    // 이전 시도가 있다면 추가 HTTP 시도 없이 종료한다. 별도의 가짜 시도 행을 만들지 않는다.
                    if (number == 1) preflightFailure(failure(AiGatewayErrorCode.TIMEOUT));
                    else finish(null, failure(AiGatewayErrorCode.TIMEOUT));
                    return;
                }
                Duration timeout = remaining.compareTo(properties.requestTimeout()) < 0 ? remaining : properties.requestTimeout();
                attempt = new Attempt(number, true);
                current = attempt;
                log.debug("AI HTTP 전송 시작: requestId={}, callId={}, attemptNo={}, timeoutMs={}",
                        requestId, callId, number, timeout.toMillis());
                // 취소와 요청 시작 사이의 짧은 경쟁 구간을 보호한다. 이 호출은 응답을 기다리지 않는다.
                attempt.http = client.complete(payload, timeout);
            }
            // 바로 결과를 꺼내지 않고 완료될 때 실행할 함수를 등록한다. 반환값을 기다리는 스레드는 없다.
            attempt.http.whenComplete((response, error) -> dispatch(
                    () -> observe(attempt, response, error),
                    () -> observe(attempt, response, failure(AiGatewayErrorCode.CAPACITY_EXCEEDED))));
        }

        private void observe(Attempt attempt, @Nullable HttpResponse<String> response, @Nullable Throwable error) {
            // 취소와 HTTP 완료가 겹쳐도 같은 시도를 두 번 기록하거나 취소 후 재시도하지 않는다.
            if (terminal.get() || !attempt.observed.compareAndSet(false, true)) return;
            AiGenerationResult generated = null;
            AiGatewayException failed = null;
            try {
                if (error != null) failed = normalize(error);
                else if (response.statusCode() == 200) generated = mapper.result(response, request.outputSchema() != null, started);
                else failed = mapper.failure(response);
            } catch (AiGatewayException exception) {
                failed = exception;
            } catch (RuntimeException exception) {
                failed = failure(AiGatewayErrorCode.INVALID_RESPONSE);
            }
            recordOutcome(attempt, response, generated, failed);
        }

        private void recordOutcome(Attempt attempt, @Nullable HttpResponse<String> response,
                                   @Nullable AiGenerationResult generated, @Nullable AiGatewayException failed) {
            // DB 큐 대기·저장 시간은 제공자 지연과 구분한다. 완료 Future의 전체 지연에는 포함한다.
            long latency = Duration.ofNanos(System.nanoTime() - attempt.started).toMillis();
            var metadata = generated != null
                    ? new LinerResponseMetadata(generated.modelName(), generated.usage(), generated.finishReason().providerValue(), generated.requestId())
                    : response == null ? LinerResponseMetadata.unknown() : mapper.metadata(response);
            var entry = new AiGenerationAttempt(callId, attempt.number, request.userId(), request.taskType(), request.promptVersion(),
                    "liner", properties.model(), metadata.modelName(), metadata.requestId(), response == null ? null : response.statusCode(),
                    attempt.httpAttempted, metadata.usage(), metadata.finishReason(), latency,
                    generated != null, failed == null ? null : failed.getErrorCode().getCode());
            // 생성 원문·프롬프트는 출력하지 않는다. 외부 문자열은 길이와 제어 문자를 정리한다.
            var event = generated != null ? log.atDebug() : log.atWarn();
            event.log("AI 시도 결과: requestId={}, callId={}, attemptNo={}, httpAttempted={}, httpStatus={}, model={}, providerRequestId={}, inputTokens={}, outputTokens={}, finishReason={}, elapsedMs={}, code={}",
                    requestId, callId, attempt.number, entry.httpAttempted(), entry.httpStatus(), LogValues.safe(metadata.modelName()),
                    LogValues.safe(metadata.requestId()), metadata.usage().inputTokens(), metadata.usage().outputTokens(),
                    LogValues.safe(metadata.finishReason()), latency, entry.errorCode());
            // 저장이 끝나거나 생략되면 다음 단계로 간다. 이 연결도 get()/join()으로 기다리지 않는다.
            recordAsync(entry).whenComplete((unused, loggingError) -> afterLog(attempt, generated, failed));
        }

        private void afterLog(Attempt attempt, @Nullable AiGenerationResult generated, @Nullable AiGatewayException failed) {
            if (terminal.get()) return;
            if (generated != null) {
                finish(new AiGenerationResult(generated.content(), generated.modelName(), generated.usage(), generated.finishReason(),
                        generated.requestId(), Duration.ofNanos(System.nanoTime() - started).toMillis()), null);
                return;
            }
            if (!failed.isRetryable() || attempt.number > properties.maxRetries()) {
                finish(null, failed);
                return;
            }
            Duration delay = retryDelay(failed);
            if (delay.compareTo(properties.maxRetryDelay()) > 0 || delay.compareTo(remaining()) >= 0) {
                finish(null, failed);
                return;
            }
            synchronized (this) {
                if (terminal.get()) return;
                try {
                    // Thread.sleep() 대신 예약한다. 대기 중에는 작업 스레드를 점유하지 않는다.
                    retry = resources.schedule(() -> dispatch(() -> startAttempt(attempt.number + 1),
                            () -> finish(null, failure(AiGatewayErrorCode.CAPACITY_EXCEEDED))), delay);
                    log.warn("AI 재시도 예약: requestId={}, callId={}, attemptNo={}, delayMs={}, code={}",
                            requestId, callId, attempt.number + 1, delay.toMillis(), failed.getErrorCode().getCode());
                } catch (RejectedExecutionException exception) {
                    finish(null, failure(AiGatewayErrorCode.PROVIDER_UNAVAILABLE));
                }
            }
        }

        private Duration remaining() {
            return properties.totalTimeout().minusNanos(System.nanoTime() - started);
        }

        private void finish(@Nullable AiGenerationResult value, @Nullable AiGatewayException error) {
            if (!terminal.compareAndSet(false, true)) return;
            // 호출부의 완료 함수가 실행되기 전에 자원을 반납한다. 호출부가 느려도 슬롯이 붙잡히지 않는다.
            cleanup(error);
            logCompletion(value, error);
            if (error == null) result.complete(value);
            else result.completeExceptionally(error);
        }

        private void logCompletion(@Nullable AiGenerationResult value, @Nullable AiGatewayException error) {
            int attemptNo;
            synchronized (this) { attemptNo = current == null ? 0 : current.number; }
            boolean cancelled = error != null && error.getErrorCode() == AiGatewayErrorCode.CANCELLED;
            var event = error == null || cancelled ? log.atInfo() : log.atWarn();
            // terminal CAS를 통과한 경로에서만 호출하므로 성공·실패·취소가 겹쳐도 최종 로그는 한 번이다.
            event.log("AI 호출 완료: requestId={}, callId={}, outcome={}, attemptNo={}, model={}, elapsedMs={}, code={}",
                    requestId, callId, cancelled ? "cancelled" : error == null ? "success" : "failure", attemptNo,
                    LogValues.safe(value == null ? null : value.modelName()),
                    Duration.ofNanos(System.nanoTime() - started).toMillis(), error == null ? null : error.getErrorCode().getCode());
        }

        private void cleanup(@Nullable AiGatewayException error) {
            synchronized (this) {
                if (retry != null) retry.cancel(false);
                if (current != null) {
                    if (current.http != null) current.http.cancel(true);
                    // 전송 중 취소/종료는 응답 미확정 실패다. 이미 관측한 응답이나 저장 중인 행은 다시 만들지 않는다.
                    if (error != null && current.observed.compareAndSet(false, true)) {
                        recordOutcome(current, null, null, error);
                    }
                }
            }
            active.remove(this);
            slots.release();
        }
    }

    /** 시도별 HTTP 취소 핸들과 한 번만 관측하기 위한 상태를 보관한다. */
    private static final class Attempt {
        private final int number;
        private final boolean httpAttempted;
        private final long started = System.nanoTime();
        private final AtomicBoolean observed = new AtomicBoolean();
        private @Nullable CompletableFuture<HttpResponse<String>> http;

        private Attempt(int number, boolean httpAttempted) {
            this.number = number;
            this.httpAttempted = httpAttempted;
        }
    }

    private CompletableFuture<Void> recordAsync(AiGenerationAttempt entry) {
        var done = new CompletableFuture<Void>();
        try {
            resources.executeLog(() -> {
                try {
                    // TransactionTemplate은 이 저장 스레드에서 실행된다. 요청 스레드의 트랜잭션을 가져오지 않는다.
                    logs.record(entry);
                } catch (RuntimeException exception) {
                    warnLogFailure(entry, exception);
                } finally {
                    // 저장 실패에도 완료시켜 원래 생성 결과/예외가 전달되도록 한다.
                    done.complete(null);
                }
            });
        } catch (RejectedExecutionException exception) {
            // DB 큐가 가득 찼거나 종료되면 호출 스레드에서 대신 저장하지 않는다. best effort 로그만 생략한다.
            log.warn("AI 로그 저장 작업 거절: callId={}, attemptNo={}, queue=log, reason=capacity_or_shutdown",
                    entry.callId(), entry.attemptNo());
            done.complete(null);
        }
        return done;
    }

    private void warnLogFailure(AiGenerationAttempt entry, RuntimeException exception) {
        log.warn("AI 호출 로그 작업 실패: callId={}, attemptNo={}, errorType={}",
                entry.callId(), entry.attemptNo(), exception.getClass().getSimpleName());
    }

    private AiGatewayException normalize(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        return error instanceof AiGatewayException known ? known : failure(AiGatewayErrorCode.PROVIDER_UNAVAILABLE);
    }

    private AiGatewayException failure(AiGatewayErrorCode code) {
        return new AiGatewayException(code, null, false, null, null);
    }

    private Duration retryDelay(AiGatewayException error) {
        long base = properties.retryBackoff().toNanos();
        Duration delay = Duration.ofNanos(ThreadLocalRandom.current().nextLong(base / 2, base + 1));
        return error.getRetryAfter() != null && error.getRetryAfter().compareTo(delay) > 0 ? error.getRetryAfter() : delay;
    }

    @PreDestroy
    public void close() {
        if (!closing.compareAndSet(false, true)) return;
        log.info("AI Gateway 종료 시작: activeCalls={}", active.size());
        // 결과를 먼저 실패로 확정하고 HTTP·예약 작업을 취소한다. 작업 풀이 종료돼도 미완료 Future가 남지 않는다.
        active.forEach(call -> call.finish(null, failure(AiGatewayErrorCode.PROVIDER_UNAVAILABLE)));
    }
}
