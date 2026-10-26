package com.mongle.backend.domain.dream.image.openai;

import com.mongle.backend.domain.dream.image.*;
import com.mongle.backend.domain.dream.image.s3.ImageS3Properties;
import com.mongle.backend.global.error.BusinessException;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.mongle.backend.domain.dream.image.ImageProviderException.Reason.*;

/** 유료 POST는 한 번만 전송한다. 네트워크 대기는 Future, JSON/base64는 제한된 응답 풀에서 처리한다. */
@Slf4j
public final class OpenAiImageGenerator implements ImageGenerator, AutoCloseable {
    static final int MAX_BASE64 = ((ImagePayload.MAX_BYTES + 2) / 3) * 4;
    static final int MAX_JSON = MAX_BASE64 + 64 * 1024;
    private final OpenAiImageProperties properties;
    private final OpenAiImagePrompt prompt;
    private final HttpClient http;
    private final URI endpoint;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Semaphore slots;
    private final ThreadPoolExecutor responses;
    private final ScheduledThreadPoolExecutor timer;
    private final Set<Call> active = ConcurrentHashMap.newKeySet();
    private final Object lifecycle = new Object();
    private final AtomicBoolean closing = new AtomicBoolean();

    public static OpenAiImageGenerator open(OpenAiImageProperties properties, ImageOptions options,
            ImageExecutionProperties execution, ImageS3Properties storage) {
        properties.validateActivation(options, execution, storage);
        var http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        try {
            return new OpenAiImageGenerator(properties, options, execution, storage, http,
                    URI.create("https://api.openai.com/v1/images/generations"));
        } catch (RuntimeException ex) { http.shutdownNow(); throw ex; }
    }

    /** 테스트는 고정 가짜 인증 정보/로컬 HTTP를 주입한다. 운영 endpoint 설정은 제공하지 않는다. */
    public OpenAiImageGenerator(OpenAiImageProperties properties, ImageOptions options,
            ImageExecutionProperties execution, ImageS3Properties storage, HttpClient http, URI endpoint) {
        if (!properties.enabled()) throw new IllegalArgumentException("활성 이미지 Provider 설정이 필요합니다.");
        properties.validateActivation(options, execution, storage);
        this.properties = properties; this.http = http; this.endpoint = endpoint;
        this.prompt = new OpenAiImagePrompt(properties);
        int capacity = execution.maxConcurrentCalls();
        slots = new Semaphore(capacity);
        int threads = Math.min(capacity, properties.responseThreads());
        responses = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), Thread.ofPlatform().name("openai-image-response-",0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        timer = new ScheduledThreadPoolExecutor(1,Thread.ofPlatform().name("openai-image-timer-",0).factory());
        timer.setRemoveOnCancelPolicy(true);
        timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    @Override public boolean available() { return !closing.get(); }

    @Override public CompletableFuture<byte[]> generate(Input input) {
        final Call call;
        synchronized (lifecycle) {
            if (closing.get() || !slots.tryAcquire())
                return CompletableFuture.failedFuture(new ImageProviderException(PROVIDER_UNAVAILABLE));
            call = new Call(); active.add(call);
        }
        call.result.whenComplete((bytes,error) -> {
            if (call.deadline != null) call.deadline.cancel(false);
            if (error != null && call.transport != null) call.transport.cancel(true);
            active.remove(call); slots.release();
            String outcome = error == null ? "BYTES_RECEIVED" : safeOutcome(error);
            log.info("이미지 Provider 완료: imageId={}, attemptId={}, requestId={}, outcome={}, status={}, elapsedMs={}",
                    input.imageId(), safeId(input.attemptId()), call.requestId, outcome, call.status,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-call.started));
        });
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(properties.totalTimeout())
                    .header("Authorization","Bearer "+properties.apiKey())
                    .header("Content-Type","application/json").header("Accept","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(prompt.request(input),StandardCharsets.UTF_8)).build();
            synchronized (lifecycle) {
                if (closing.get() || call.result.isDone()) throw new ImageProviderException(PROVIDER_UNAVAILABLE);
                call.transport = http.sendAsync(request, info -> {
                    call.status = info.statusCode();
                    call.requestId = safeId(info.headers().firstValue("x-request-id").orElse(null));
                    if (info.statusCode() < 200 || info.statusCode() >= 300) {
                        call.result.completeExceptionally(httpFailure(info.statusCode()));
                        return HttpResponse.BodySubscribers.replacing(new byte[0]);
                    }
                    if (info.headers().firstValueAsLong("Content-Length").orElse(0) > MAX_JSON) {
                        call.result.completeExceptionally(invalid());
                        return HttpResponse.BodySubscribers.replacing(new byte[0]);
                    }
                    return new LimitedImageBody(MAX_JSON);
                });
            }
            // 전체 본문 수신과 응답 큐 대기를 제한한다. HTTP 헤더 수신만으로 타이머를 제거하지 않는다.
            call.deadline = timer.schedule(() -> call.result.completeExceptionally(new ImageProviderException(PROVIDER_TIMEOUT)),
                    properties.totalTimeout().toNanos(),TimeUnit.NANOSECONDS);
            if (call.result.isDone()) {
                call.deadline.cancel(false); call.transport.cancel(true);
            }
            call.transport.whenComplete((response,error) -> {
                if (call.result.isDone()) return;
                if (error != null) {
                    var cause = unwrap(error);
                    call.result.completeExceptionally(cause instanceof BusinessException business
                                    && business.getErrorCode() == ImageErrorCode.INVALID_OUTPUT ? business
                            : new ImageProviderException(cause instanceof HttpTimeoutException
                                    ? PROVIDER_TIMEOUT : PROVIDER_UNAVAILABLE));
                    return;
                }
                try {
                    responses.execute(() -> {
                        if (call.result.isDone()) return;
                        try {
                            if (!response.headers().firstValue("Content-Type").orElse("")
                                    .split(";",2)[0].strip().equalsIgnoreCase("application/json")) throw invalid();
                            call.result.complete(decode(response.body()));
                        } catch (RuntimeException ex) { call.result.completeExceptionally(invalid()); }
                    });
                } catch (RejectedExecutionException ex) {
                    call.result.completeExceptionally(new ImageProviderException(PROVIDER_UNAVAILABLE));
                }
            });
        } catch (RuntimeException ex) {
            call.result.completeExceptionally(ex instanceof BusinessException ? ex : new ImageProviderException(PROVIDER_UNAVAILABLE));
        }
        // 외부 취소는 내부 통신/완료 처리를 취소하지 않는다. 종료/타임아웃만 통신을 중단한다.
        return call.result.copy();
    }

    private byte[] decode(byte[] body) {
        if (body == null || body.length > MAX_JSON) throw invalid();
        var root = json.readTree(body);
        var data = root.path("data");
        if (!data.isArray() || data.size() != 1 || !data.get(0).path("b64_json").isString()) throw invalid();
        String encoded = data.get(0).path("b64_json").asString();
        if (encoded.isEmpty() || encoded.length() > MAX_BASE64) throw invalid();
        if (root.has("output_format") && !properties.outputFormat().equals(root.path("output_format").asString())) throw invalid();
        byte[] decoded = Base64.getDecoder().decode(encoded);
        if (decoded.length == 0 || decoded.length > ImagePayload.MAX_BYTES) throw invalid();
        return decoded;
    }

    private static BusinessException invalid() { return new BusinessException(ImageErrorCode.INVALID_OUTPUT); }
    private static Throwable unwrap(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        return error;
    }
    private static String safeOutcome(Throwable error) {
        var cause = unwrap(error);
        if (cause instanceof ImageProviderException provider) return provider.failureCode();
        if (cause instanceof BusinessException business && business.getErrorCode() == ImageErrorCode.INVALID_OUTPUT) return "INVALID_OUTPUT";
        return "REJECTED";
    }
    private static String safeId(String value) {
        return value != null && value.matches("[a-zA-Z0-9_-]{1,100}") ? value : "unavailable";
    }
    private static ImageProviderException httpFailure(int status) {
        return new ImageProviderException(status == 401 || status == 403 ? PROVIDER_AUTH_FAILED
                : status == 429 ? PROVIDER_RATE_LIMITED : status >= 500 ? PROVIDER_UNAVAILABLE : PROVIDER_REJECTED);
    }

    private static final class Call {
        final CompletableFuture<byte[]> result = new CompletableFuture<>();
        final long started = System.nanoTime();
        volatile CompletableFuture<HttpResponse<byte[]>> transport;
        volatile ScheduledFuture<?> deadline;
        volatile int status;
        volatile String requestId = "unavailable";
    }

    @Override public void close() {
        synchronized (lifecycle) {
            if (!closing.compareAndSet(false,true)) return;
            active.forEach(call -> call.result.completeExceptionally(new ImageProviderException(PROVIDER_UNAVAILABLE)));
        }
        timer.shutdownNow(); responses.shutdownNow(); http.shutdownNow();
    }
}
