package com.mongle.backend.domain.ai.liner;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

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

    @BeforeEach
    void startProvider() throws Exception {
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
        if (client != null) client.shutdownNow();
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void sendsTextThroughCommonInterfaceAndPreservesMissingMetadata() {
        replies.add(new Reply(200, """
                {"id":"body-completion-id","choices":[{"message":{"role":"assistant","content":"  생성 원문\\n"}}]}
                """, Map.of(), Duration.ZERO));
        var result = gateway(properties("test-key", Duration.ofSeconds(2), Duration.ofSeconds(3))).generate(request(null));

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

        var result = gateway(properties("test-key", Duration.ofSeconds(2), Duration.ofSeconds(3))).generate(request(schema));

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
        var result = gateway(properties("test-key", Duration.ofSeconds(2), Duration.ofSeconds(3))).generate(request(null));
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
        assertThatThrownBy(() -> gateway.generate(request(null))).isInstanceOfSatisfying(AiGatewayException.class, failure -> {
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
        assertThatThrownBy(() -> gateway.generate(request(null))).isInstanceOfSatisfying(AiGatewayException.class,
                failure -> assertThat(failure.getRetryAfter()).isEqualTo(Duration.ofSeconds(30)));
        assertThat(received).hasSize(1);
    }

    @ParameterizedTest
    @MethodSource("unusableResponses")
    void rejectsUnusableResultsWithoutRetry(String body, boolean expectsJson, AiGatewayErrorCode expected) {
        replies.add(new Reply(200, body, Map.of("x-request-id", "bad-result"), Duration.ZERO));
        AiJsonSchema schema = expectsJson ? new AiJsonSchema("example", json.readTree("{\"type\":\"object\"}"), true) : null;
        AiGateway gateway = gateway(properties("test-key", Duration.ofSeconds(2), Duration.ofSeconds(3)));
        assertThatThrownBy(() -> gateway.generate(request(schema))).isInstanceOfSatisfying(AiGatewayException.class,
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
        assertThatThrownBy(() -> gateway.generate(request(null))).isInstanceOfSatisfying(AiGatewayException.class, failure -> {
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
        assertThatThrownBy(() -> gateway.generate(request(null))).isInstanceOfSatisfying(AiGatewayException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(AiGatewayErrorCode.TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2600));
        assertThat(received).hasSize(2);
    }

    @Test
    void rejectsRedirectWithoutForwardingCredentialsAndMissingKeyWithoutHttp() {
        replies.add(new Reply(302, "{}", Map.of("Location", endpoint.toString()), Duration.ZERO));
        AiGateway configured = gateway(properties("test-key", Duration.ofSeconds(2), Duration.ofSeconds(3)));
        assertThatThrownBy(() -> configured.generate(request(null))).isInstanceOfSatisfying(AiGatewayException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(AiGatewayErrorCode.INVALID_RESPONSE));
        assertThat(received).hasSize(1);
        var withoutKey = properties("", Duration.ofSeconds(2), Duration.ofSeconds(3));
        AiGateway missing = new LinerAiGateway(withoutKey, new LinerClient(client, withoutKey), new LinerPayloadMapper(json, withoutKey));
        assertThatThrownBy(() -> missing.generate(request(null))).isInstanceOfSatisfying(AiGatewayException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(AiGatewayErrorCode.AUTHENTICATION_FAILED));
        assertThat(received).hasSize(1);
    }

    private AiGateway gateway(LinerProperties properties) {
        client = new LinerConfig().linerHttpClient(properties);
        return new LinerAiGateway(properties, new LinerClient(client, properties), new LinerPayloadMapper(json, properties));
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

    private record Reply(int status, String body, Map<String, String> headers, Duration bodyDelay) { }
    private record Received(String method, String authorization, String contentType, String body) { }
}
