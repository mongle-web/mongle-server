package com.mongle.backend.domain.ai.liner;

import com.mongle.backend.domain.ai.config.AiAsyncProperties;
import com.mongle.backend.domain.ai.config.AiAsyncResources;
import com.mongle.backend.domain.ai.dto.request.AiGenerationRequest;
import com.mongle.backend.domain.ai.dto.request.AiJsonSchema;
import com.mongle.backend.domain.ai.dto.request.AiMessage;
import com.mongle.backend.domain.ai.dto.response.AiFinishReason;
import com.mongle.backend.domain.ai.entity.AiTaskType;
import com.mongle.backend.domain.ai.error.AiGatewayErrorCode;
import com.mongle.backend.domain.ai.error.AiGatewayException;
import com.mongle.backend.domain.ai.gateway.AiGateway;
import com.mongle.backend.domain.ai.liner.client.LinerClient;
import com.mongle.backend.domain.ai.liner.config.LinerConfig;
import com.mongle.backend.domain.ai.liner.config.LinerProperties;
import com.mongle.backend.domain.ai.liner.mapper.LinerPayloadMapper;
import com.sun.net.httpserver.HttpServer;
import com.mongle.backend.domain.ai.service.AiGenerationLogService;
import com.mongle.backend.domain.ai.dto.logging.AiGenerationAttempt;
import com.mongle.backend.global.logging.HttpRequestLoggingFilter;
import com.mongle.backend.global.logging.support.LogCapture;
import org.slf4j.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static com.mongle.backend.domain.ai.support.AiGatewayTestAwait.await;

import static org.assertj.core.api.Assertions.*;

/** 실제 HTTP 직렬화·응답·대기를 검증하지만 외부 네트워크와 유료 API 키를 사용하지 않는다. */
class LinerAiGatewayIntegrationTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Received> received = new ConcurrentLinkedQueue<>();
    private HttpServer server;
    private ExecutorService executor;
    private HttpClient client;
    private URI endpoint;
    private AiAsyncResources resources;
    private final java.util.List<LinerAiGateway> gateways = new java.util.ArrayList<>();

    @BeforeEach
    void startProvider() throws Exception {
        resources = new AiAsyncResources(new AiAsyncProperties(4, 2, 16, 2, 16));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/chat/completions", exchange -> {
            received.add(new Received(exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            Reply reply = replies.poll();
            if (reply == null) {
                reply = new Reply(500, "{}", Map.of(), Duration.ZERO);
            }
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            reply.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
            // 헤더를 먼저 보내고 본문만 지연시킬 수 있어, 헤더 이후에도 타임아웃이 적용되는지 확인한다.
            exchange.sendResponseHeaders(reply.status(), body.length);
            try {
                if (reply.bodyGate() != null) reply.bodyGate().await(5, TimeUnit.SECONDS);
                Thread.sleep(reply.bodyDelay());
                exchange.getResponseBody().write(body);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions");
    }

    @AfterEach
    void stopProvider() {
        gateways.forEach(LinerAiGateway::close);
        if (resources != null) resources.close();
        if (client != null) client.shutdownNow();
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void logsCorrelatedRetryAndCompletionWithoutPromptsKeysOrGeneratedContent() {
        replies.add(new Reply(429, "{\"error\":{\"message\":\"provider-error-secret\"}}", Map.of(), Duration.ZERO));
        var provider = (tools.jackson.databind.node.ObjectNode) json.readTree(success("response-secret", "stop"));
        provider.put("model", "reported\nmodel");
        provider.putObject("usage").put("prompt_tokens", 10).put("completion_tokens", 20).put("total_tokens", 30);
        replies.add(new Reply(200, json.writeValueAsString(provider), Map.of("x-request-id", "provider-trace"), Duration.ZERO));
        var logger = org.mockito.Mockito.mock(AiGenerationLogService.class);
        var gateway = gateway(properties("api-key-secret", Duration.ofSeconds(2), Duration.ofSeconds(3)), logger);
        var request = new AiGenerationRequest(7L, AiTaskType.DREAM_STRUCTURE, "test-v1",
                List.of(new AiMessage(AiMessage.Role.USER, "prompt-secret")), null);
        try (var capture = new LogCapture(LinerAiGateway.class)) {
            CompletableFuture<?> pending;
            MDC.put(HttpRequestLoggingFilter.REQUEST_ID, "http-trace");
            try {
                pending = gateway.generate(request);
            } finally {
                // 실제 HTTP 필터처럼 호출 스레드의 MDC를 즉시 제거해도 비동기 로그가 연결돼야 한다.
                MDC.remove(HttpRequestLoggingFilter.REQUEST_ID);
            }
            await(pending);
            var rows = org.mockito.ArgumentCaptor.forClass(AiGenerationAttempt.class);
            org.mockito.Mockito.verify(logger, org.mockito.Mockito.times(2)).record(rows.capture());
            String callId = rows.getValue().callId();
            assertThat(capture.messages()).allSatisfy(message -> assertThat(message).contains("requestId=http-trace", "callId=" + callId));
            assertThat(capture.messages()).anyMatch(m -> m.contains("AI 재시도 예약") && m.contains("attemptNo=2"));
            assertThat(capture.messages().stream().filter(m -> m.contains("AI 호출 완료"))).hasSize(1);
            String text = String.join("\n", capture.messages());
            assertThat(text).contains("outcome=success", "model=reported_model", "inputTokens=10", "outputTokens=20")
                    .doesNotContain("prompt-secret", "response-secret", "api-key-secret", "provider-error-secret", "reported\nmodel");
        }
    }

    @Test
    void sendsTextThroughCommonInterfaceAndPreservesMissingMetadata() {
        replies.add(new Reply(200, """
                {"id":"body-completion-id","choices":[{"message":{"role":"assistant","content":"  생성 원문\\n"}}]}
                """, Map.of(), Duration.ZERO));
        var result = await(gateway(properties("test-key", Duration.ofSeconds(2), Duration.ofSeconds(3))).generate(request(null)));

        assertThat(result.content()).isEqualTo("  생성 원문\n");
        assertThat(result.modelName()).isNull();
        assertThat(result.requestId()).isNull();
        assertThat(result.usage().inputTokens()).isNull();
        assertThat(result.usage().totalTokens()).isNull();
        assertThat(result.finishReason().type()).isEqualTo(AiFinishReason.Type.UNKNOWN);
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
        Received sent = received.element();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.authorization()).isEqualTo("Bearer test-key");
        assertThat(sent.contentType()).isEqualTo("application/json");
        JsonNode body = json.readTree(sent.body());
        assertThat(body.path("model").asString()).isEqualTo("liner-mark-1.1");
        assertThat(body.path("stream").asBoolean()).isFalse();
        assertThat(body.path("max_completion_tokens").asInt()).isEqualTo(4096);
        assertThat(body.path("reasoning_effort").asString()).isEqualTo("medium");
        assertThat(body.at("/response_format/type").asString()).isEqualTo("text");
        assertThat(body.at("/messages/0/role").asString()).isEqualTo("system");
        assertThat(body.at("/messages/1/content").asString()).isEqualTo("  입력 원문\n");
        assertThat(sent.body()).doesNotContain("userId", "taskType", "promptVersion", "test-key");
    }

    @Test
    void sendsSchemaAndNormalizesReportedUsageWithoutLosingZeros() {
        var schema = new AiJsonSchema("example", json.readTree("""
                {"type":"object","properties":{"value":{"type":"string"}},"required":["value"],"additionalProperties":false}
                """), true);
        var provider = json.readTree(success("{\"value\":\"ok\"}", "stop"));
        ((tools.jackson.databind.node.ObjectNode) provider).put("model", "reported-model");
        var usage = ((tools.jackson.databind.node.ObjectNode) provider).putObject("usage");
        usage.put("prompt_tokens", 10).put("completion_tokens", 20).put("total_tokens", 30);
        usage.putObject("prompt_tokens_details").put("cached_tokens", 0);
        usage.putObject("completion_tokens_details").put("reasoning_tokens", 5);
        replies.add(new Reply(200, json.writeValueAsString(provider), Map.of("x-request-id", "header-trace"), Duration.ZERO));

        var result = await(gateway(properties("test-key", Duration.ofSeconds(2), Duration.ofSeconds(3))).generate(request(schema)));

        assertThat(result.content()).isEqualTo("{\"value\":\"ok\"}");
        assertThat(result.modelName()).isEqualTo("reported-model");
        assertThat(result.requestId()).isEqualTo("header-trace");
        assertThat(result.finishReason().type()).isEqualTo(AiFinishReason.Type.STOP);
        assertThat(result.usage().inputTokens()).isEqualTo(10);
        assertThat(result.usage().outputTokens()).isEqualTo(20);
        assertThat(result.usage().totalTokens()).isEqualTo(30);
        assertThat(result.usage().cachedInputTokens()).isZero();
        assertThat(result.usage().reasoningTokens()).isEqualTo(5);
        JsonNode sent = json.readTree(received.element().body());
        assertThat(sent.at("/response_format/type").asString()).isEqualTo("json_schema");
        assertThat(sent.at("/response_format/json_schema/name").asString()).isEqualTo("example");
        assertThat(sent.at("/response_format/json_schema/strict").asBoolean()).isTrue();
        assertThat(sent.at("/response_format/json_schema/schema")).isEqualTo(schema.schema());
    }

    @Test
    void waitsForRetryAfterThenReturnsResultIncludingBothAttemptsLatency() {
        replies.add(new Reply(429, "{\"error\":{\"retryable\":true}}", Map.of("Retry-After", "1"), Duration.ZERO));
        replies.add(new Reply(200, success("재시도 결과", "stop"), Map.of(), Duration.ZERO));
        var result = await(gateway(properties("test-key", Duration.ofSeconds(2), Duration.ofSeconds(3))).generate(request(null)));
        assertThat(result.content()).isEqualTo("재시도 결과");
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(1000);
        assertThat(received).hasSize(2);
        assertThat(received.stream().map(Received::body).distinct()).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({
            "401,false,AUTHENTICATION_FAILED,1", "402,true,INSUFFICIENT_CREDIT,1",
            "429,false,RATE_LIMITED,1", "502,false,PROVIDER_UNAVAILABLE,1",
            "500,true,PROVIDER_UNAVAILABLE,2", "504,true,TIMEOUT,1",
            "422,true,INVALID_REQUEST,1"
    })
    void normalizesFailuresAndLimitsRetries(int status, boolean retryable, AiGatewayErrorCode expected, int attempts) {
        String body = "{\"error\":{\"message\":\"외부 민감 정보\",\"retryable\":" + retryable + "}}";
        for (int i = 0; i < attempts; i++) replies.add(new Reply(status, body, Map.of("x-request-id", "failure-trace"), Duration.ZERO));
        AiGateway gateway = gateway(properties("test-key", Duration.ofSeconds(2), Duration.ofSeconds(3)));
        assertThatThrownBy(() -> await(gateway.generate(request(null)))).isInstanceOfSatisfying(AiGatewayException.class, failure -> {
            assertThat(failure.getErrorCode()).isEqualTo(expected);
            assertThat(failure.getRequestId()).isEqualTo("failure-trace");
            assertThat(failure.getMessage()).doesNotContain("외부 민감 정보", "test-key");
            assertThat(failure.getCause()).isNull();
        });
        assertThat(received).hasSize(attempts);
    }

    @Test
    void doesNotShortenExcessiveRetryAfterOrSpendMoreThanRemainingBudget() {
        replies.add(new Reply(429, "{\"error\":{\"retryable\":true}}", Map.of("Retry-After", "30"), Duration.ZERO));
        AiGateway gateway = gateway(properties("test-key", Duration.ofSeconds(2), Duration.ofSeconds(3)));
        assertThatThrownBy(() -> await(gateway.generate(request(null)))).isInstanceOfSatisfying(AiGatewayException.class,
                failure -> assertThat(failure.getRetryAfter()).isEqualTo(Duration.ofSeconds(30)));
        assertThat(received).hasSize(1);
    }

    @ParameterizedTest
    @MethodSource("unusableResponses")
    void rejectsUnusableResultsWithoutRetry(String body, boolean expectsJson, AiGatewayErrorCode expected) {
        replies.add(new Reply(200, body, Map.of("x-request-id", "bad-result"), Duration.ZERO));
        AiJsonSchema schema = expectsJson ? new AiJsonSchema("example", json.readTree("{\"type\":\"object\"}"), true) : null;
        AiGateway gateway = gateway(properties("test-key", Duration.ofSeconds(2), Duration.ofSeconds(3)));
        assertThatThrownBy(() -> await(gateway.generate(request(schema)))).isInstanceOfSatisfying(AiGatewayException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(expected));
        assertThat(received).hasSize(1);
    }

    static Stream<Arguments> unusableResponses() {
        return Stream.of(
                Arguments.of("{\"choices\":[]}", false, AiGatewayErrorCode.INVALID_RESPONSE),
                Arguments.of(success(" ", "stop"), false, AiGatewayErrorCode.INVALID_RESPONSE),
                Arguments.of(success("끊긴 내용", "length"), false, AiGatewayErrorCode.INCOMPLETE_RESPONSE),
                Arguments.of(success("도구 호출", "tool_calls"), false, AiGatewayErrorCode.INCOMPLETE_RESPONSE),
                Arguments.of(success("{\"value\":\"ok\"} 뒤의 설명", "stop"), true, AiGatewayErrorCode.INVALID_RESPONSE),
                Arguments.of(success("ok", "stop").replace("\"choices\":", "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":9},\"choices\":"), false, AiGatewayErrorCode.INVALID_RESPONSE));
    }

    @Test
    void timesOutWhileReadingBodyWithoutReissuingGeneration() {
        replies.add(new Reply(200, success("늦은 응답", "stop"), Map.of(), Duration.ofSeconds(2)));
        AiGateway gateway = gateway(properties("test-key", Duration.ofMillis(700), Duration.ofMillis(900)));
        long started = System.nanoTime();
        assertThatThrownBy(() -> await(gateway.generate(request(null)))).isInstanceOfSatisfying(AiGatewayException.class, failure -> {
            assertThat(failure.getErrorCode()).isEqualTo(AiGatewayErrorCode.TIMEOUT);
            assertThat(failure.isRetryable()).isFalse();
        });
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1800));
        assertThat(received).hasSize(1);
    }

    @Test
    void appliesTotalBudgetToSecondAttemptInsteadOfResettingTimeout() {
        replies.add(new Reply(503, "{\"error\":{\"retryable\":true}}", Map.of("Retry-After", "1"), Duration.ZERO));
        replies.add(new Reply(200, success("늦은 두 번째 응답", "stop"), Map.of(), Duration.ofSeconds(3)));
        AiGateway gateway = gateway(properties("test-key", Duration.ofMillis(1500), Duration.ofMillis(1800)));
        long started = System.nanoTime();
        assertThatThrownBy(() -> await(gateway.generate(request(null)))).isInstanceOfSatisfying(AiGatewayException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(AiGatewayErrorCode.TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2600));
        assertThat(received).hasSize(2);
    }

    @Test
    void rejectsRedirectWithoutForwardingCredentialsAndMissingKeyWithoutHttp() {
        replies.add(new Reply(302, "{}", Map.of("Location", endpoint.toString()), Duration.ZERO));
        AiGateway configured = gateway(properties("test-key", Duration.ofSeconds(2), Duration.ofSeconds(3)));
        assertThatThrownBy(() -> await(configured.generate(request(null)))).isInstanceOfSatisfying(AiGatewayException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(AiGatewayErrorCode.INVALID_RESPONSE));
        assertThat(received).hasSize(1);
        var withoutKey = properties("", Duration.ofSeconds(2), Duration.ofSeconds(3));
        LinerAiGateway missing = new LinerAiGateway(withoutKey, new LinerClient(client, withoutKey, resources), new LinerPayloadMapper(json, withoutKey), org.mockito.Mockito.mock(AiGenerationLogService.class), resources);
        gateways.add(missing);
        assertThatThrownBy(() -> await(missing.generate(request(null)))).isInstanceOfSatisfying(AiGatewayException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(AiGatewayErrorCode.AUTHENTICATION_FAILED));
        assertThat(received).hasSize(1);
    }

    @Test
    void returnsFutureBeforeBodyArrivesAndLogsOnDedicatedWorker() throws Exception {
        var bodyGate = new CountDownLatch(1);
        replies.add(new Reply(200, success("비동기 완료", "stop"), Map.of(), Duration.ZERO, bodyGate));
        var logger = org.mockito.Mockito.mock(AiGenerationLogService.class);
        var logThread = new AtomicReference<String>();
        org.mockito.Mockito.doAnswer(invocation -> {
            logThread.set(Thread.currentThread().getName());
            return null;
        }).when(logger).record(org.mockito.ArgumentMatchers.any());
        var configured = gateway(properties("test-key", Duration.ofSeconds(2), Duration.ofSeconds(3)), logger);
        var future = configured.generate(request(null));
        try {
            waitUntil(() -> received.size() == 1);
            // 서버가 본문을 보내지 않았어도 generate()는 반환한다. 동기 get()이면 이 단언 전에 막힌다.
            assertThat(future).isNotDone();
            org.mockito.Mockito.verifyNoInteractions(logger);
        } finally {
            bodyGate.countDown();
        }
        assertThat(await(future).content()).isEqualTo("비동기 완료");
        assertThat(logThread.get()).startsWith("ai-log-");
    }

    @Test
    void rejectsExcessCallsAndCancellationReleasesSlotWithoutDuplicateLogs() throws Exception {
        resources.close();
        resources = new AiAsyncResources(new AiAsyncProperties(1, 2, 16, 2, 16));
        var bodyGate = new CountDownLatch(1);
        replies.add(new Reply(200, success("취소할 응답", "stop"), Map.of(), Duration.ZERO, bodyGate));
        var logger = org.mockito.Mockito.mock(AiGenerationLogService.class);
        var configured = gateway(properties("test-key", Duration.ofSeconds(2), Duration.ofSeconds(3)), logger);
        var first = configured.generate(request(null));
        try {
            waitUntil(() -> received.size() == 1);
            assertThatThrownBy(() -> await(configured.generate(request(null)))).isInstanceOfSatisfying(AiGatewayException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo(AiGatewayErrorCode.CAPACITY_EXCEEDED));
            assertThat(received).hasSize(1);
            assertThat(first.cancel(true)).isTrue();
            assertThat(first).isCancelled();
            replies.add(new Reply(200, success("새 호출", "stop"), Map.of(), Duration.ZERO));
            assertThat(await(configured.generate(request(null))).content()).isEqualTo("새 호출");
            assertThat(received).hasSize(2);
            org.mockito.Mockito.verify(logger, org.mockito.Mockito.timeout(1000)).record(org.mockito.ArgumentMatchers.argThat(
                    entry -> "AI_503_5".equals(entry.errorCode()) && entry.httpAttempted() && entry.attemptNo() == 1));
            org.mockito.Mockito.verify(logger, org.mockito.Mockito.times(2)).record(org.mockito.ArgumentMatchers.any());
        } finally {
            bodyGate.countDown();
        }
    }

    @Test
    void cancellationDuringRetryWaitPreventsSecondHttpAttempt() throws Exception {
        replies.add(new Reply(429, "{\"error\":{\"retryable\":true}}", Map.of("Retry-After", "1"), Duration.ZERO));
        replies.add(new Reply(200, success("호출하면 안 되는 응답", "stop"), Map.of(), Duration.ZERO));
        var logged = new CountDownLatch(1);
        var logger = org.mockito.Mockito.mock(AiGenerationLogService.class);
        org.mockito.Mockito.doAnswer(invocation -> { logged.countDown(); return null; })
                .when(logger).record(org.mockito.ArgumentMatchers.any());
        var configured = gateway(properties("test-key", Duration.ofSeconds(2), Duration.ofSeconds(3)), logger);
        var future = configured.generate(request(null));
        assertThat(logged.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(future.cancel(true)).isTrue();
        // 재시도 예약 시각을 지나도 요청이 추가되지 않는지 같은 타이머의 관측 지점에서 확인한다.
        var afterRetryTime = new CountDownLatch(1);
        resources.schedule(afterRetryTime::countDown, Duration.ofMillis(1200));
        assertThat(afterRetryTime.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);
        org.mockito.Mockito.verify(logger, org.mockito.Mockito.times(1)).record(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void fullLogQueueDoesNotReplaceSuccessOrOriginalProviderFailure() throws Exception {
        resources.close();
        resources = new AiAsyncResources(new AiAsyncProperties(4, 2, 16, 1, 1));
        var workerStarted = new CountDownLatch(1);
        var releaseWorker = new CountDownLatch(1);
        resources.executeLog(() -> {
            workerStarted.countDown();
            try { releaseWorker.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        });
        assertThat(workerStarted.await(1, TimeUnit.SECONDS)).isTrue();
        resources.executeLog(() -> { }); // 유일한 대기 칸도 채워 기록 작업의 제출 거절을 일으킨다.
        replies.add(new Reply(200, success("로그 큐 포화에도 성공", "stop"), Map.of(), Duration.ZERO));
        replies.add(new Reply(401, "{}", Map.of("x-request-id", "auth-trace"), Duration.ZERO));
        var logger = org.mockito.Mockito.mock(AiGenerationLogService.class);
        var configured = gateway(properties("test-key", Duration.ofSeconds(2), Duration.ofSeconds(3)), logger);
        try {
            assertThat(await(configured.generate(request(null))).content()).isEqualTo("로그 큐 포화에도 성공");
            assertThatThrownBy(() -> await(configured.generate(request(null)))).isInstanceOfSatisfying(AiGatewayException.class, error -> {
                assertThat(error.getErrorCode()).isEqualTo(AiGatewayErrorCode.AUTHENTICATION_FAILED);
                assertThat(error.getRequestId()).isEqualTo("auth-trace");
            });
            org.mockito.Mockito.verifyNoInteractions(logger);
        } finally {
            releaseWorker.countDown();
        }
    }

    @Test
    void shutdownCompletesPendingCallsAndRefusesNewHttpRequests() throws Exception {
        var bodyGate = new CountDownLatch(1);
        replies.add(new Reply(200, success("진행 중", "stop"), Map.of(), Duration.ZERO, bodyGate));
        var logger = org.mockito.Mockito.mock(AiGenerationLogService.class);
        var configured = gateway(properties("test-key", Duration.ofSeconds(2), Duration.ofSeconds(3)), logger);
        var future = configured.generate(request(null));
        try {
            waitUntil(() -> received.size() == 1);
            configured.close();
            assertThatThrownBy(() -> await(future)).isInstanceOfSatisfying(AiGatewayException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo(AiGatewayErrorCode.PROVIDER_UNAVAILABLE));
            assertThatThrownBy(() -> await(configured.generate(request(null)))).isInstanceOf(AiGatewayException.class);
            assertThat(received).hasSize(1);
            org.mockito.Mockito.verify(logger, org.mockito.Mockito.timeout(1000)).record(org.mockito.ArgumentMatchers.argThat(
                    entry -> "AI_503_3".equals(entry.errorCode()) && entry.httpAttempted()));
        } finally {
            bodyGate.countDown();
        }
    }

    @Test
    void clientCancellationAndTimeoutReachTheOriginalHttpFuture() throws Exception {
        var rawFirst = new CompletableFuture<java.net.http.HttpResponse<String>>();
        var rawSecond = new CompletableFuture<java.net.http.HttpResponse<String>>();
        var transport = org.mockito.Mockito.mock(HttpClient.class);
        org.mockito.Mockito.when(transport.sendAsync(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.<java.net.http.HttpResponse.BodyHandler<String>>any())).thenReturn(rawFirst, rawSecond);
        var configured = new LinerClient(transport, properties("test-key", Duration.ofSeconds(2), Duration.ofSeconds(3)), resources);
        var cancelled = configured.complete("{}", Duration.ofSeconds(2));
        assertThat(cancelled.cancel(true)).isTrue();
        assertThat(rawFirst).isCancelled();
        assertThatThrownBy(() -> await(configured.complete("{}", Duration.ofMillis(100)))).isInstanceOfSatisfying(AiGatewayException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(AiGatewayErrorCode.TIMEOUT));
        waitUntil(rawSecond::isCancelled);
    }

    @Test
    void responseQueueRejectionCompletesFutureAndReturnsAdmissionSlot() throws Exception {
        resources.close();
        resources = new AiAsyncResources(new AiAsyncProperties(1, 1, 1, 1, 16));
        var workerStarted = new CountDownLatch(1);
        var releaseWorker = new CountDownLatch(1);
        resources.executeResponse(() -> {
            workerStarted.countDown();
            try { releaseWorker.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        });
        assertThat(workerStarted.await(1, TimeUnit.SECONDS)).isTrue();
        var queueDrained = new CountDownLatch(1);
        resources.executeResponse(queueDrained::countDown);
        var configured = gateway(properties("test-key", Duration.ofSeconds(2), Duration.ofSeconds(3)));
        try {
            assertThatThrownBy(() -> await(configured.generate(request(null)))).isInstanceOfSatisfying(AiGatewayException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo(AiGatewayErrorCode.CAPACITY_EXCEEDED));
            assertThat(received).isEmpty();
        } finally {
            releaseWorker.countDown();
        }
        assertThat(queueDrained.await(1, TimeUnit.SECONDS)).isTrue();
        replies.add(new Reply(200, success("슬롯 복구", "stop"), Map.of(), Duration.ZERO));
        assertThat(await(configured.generate(request(null))).content()).isEqualTo("슬롯 복구");
    }

    private void waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private LinerAiGateway gateway(LinerProperties properties) {
        return gateway(properties, org.mockito.Mockito.mock(AiGenerationLogService.class));
    }

    private LinerAiGateway gateway(LinerProperties properties, AiGenerationLogService logger) {
        client = new LinerConfig().linerHttpClient(properties);
        var gateway = new LinerAiGateway(properties, new LinerClient(client, properties, resources), new LinerPayloadMapper(json, properties), logger, resources);
        gateways.add(gateway);
        return gateway;
    }

    private LinerProperties properties(String key, Duration requestTimeout, Duration totalTimeout) {
        return new LinerProperties(key, endpoint, "liner-mark-1.1", 4096, "medium",
                Duration.ofMillis(300), requestTimeout, totalTimeout, 1, Duration.ofMillis(10), Duration.ofSeconds(5));
    }

    private AiGenerationRequest request(AiJsonSchema schema) {
        return new AiGenerationRequest(7L, AiTaskType.DREAM_STRUCTURE, "test-v1", List.of(
                new AiMessage(AiMessage.Role.SYSTEM, "모의 서버 검증 지시"),
                new AiMessage(AiMessage.Role.USER, "  입력 원문\n")), schema);
    }

    private static String success(String content, String finishReason) {
        JsonMapper mapper = JsonMapper.builder().build();
        var root = mapper.createObjectNode();
        root.put("id", "body-completion-id");
        var choice = root.putArray("choices").addObject();
        choice.put("finish_reason", finishReason);
        choice.putObject("message").put("role", "assistant").put("content", content);
        return mapper.writeValueAsString(root);
    }

    private record Reply(int status, String body, Map<String, String> headers, Duration bodyDelay, CountDownLatch bodyGate) {
        private Reply(int status, String body, Map<String, String> headers, Duration bodyDelay) {
            this(status, body, headers, bodyDelay, null);
        }
    }
    private record Received(String method, String authorization, String contentType, String body) { }
}
