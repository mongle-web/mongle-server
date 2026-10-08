package com.mongle.backend.domain.ai.liner;

import com.mongle.backend.domain.ai.config.AiAsyncResources;
import com.mongle.backend.domain.ai.dto.request.AiGenerationRequest;
import com.mongle.backend.domain.ai.dto.request.AiMessage;
import com.mongle.backend.domain.ai.entity.AiCostStatus;
import com.mongle.backend.domain.ai.entity.AiGenerationLog;
import com.mongle.backend.domain.ai.entity.AiTaskType;
import com.mongle.backend.domain.ai.error.AiGatewayErrorCode;
import com.mongle.backend.domain.ai.error.AiGatewayException;
import com.mongle.backend.domain.ai.gateway.AiGateway;
import com.mongle.backend.domain.ai.liner.client.LinerClient;
import com.mongle.backend.domain.ai.liner.config.LinerProperties;
import com.mongle.backend.domain.ai.liner.mapper.LinerPayloadMapper;
import com.mongle.backend.domain.ai.repository.AiGenerationLogRepository;
import com.mongle.backend.domain.ai.service.AiGenerationLogService;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.mongle.backend.domain.ai.support.AiGatewayTestAwait.await;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 Spring Gateway → 로컬 HTTP → 비용 계산 → H2 저장과 트랜잭션 분리를 검증한다. 유료 호출은 없다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mongle-ai-logging;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema-initial.sql",
        "spring.jpa.hibernate.ddl-auto=validate",
        "mongle.ai.liner.api-key=logging-test-key",
        "mongle.ai.liner.connect-timeout=300ms",
        "mongle.ai.liner.request-timeout=700ms",
        "mongle.ai.liner.total-timeout=3s",
        "mongle.ai.liner.retry-backoff=10ms"
})
@ActiveProfiles("test")
class AiGenerationLoggingIntegrationTest {
    private static final ConcurrentLinkedQueue<Reply> REPLIES = new ConcurrentLinkedQueue<>();
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer SERVER = startProvider();

    @Autowired private AiGateway gateway;
    @Autowired private AiAsyncResources resources;
    @Autowired private AiGenerationLogRepository logs;
    @Autowired private UserRepository users;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private LinerProperties properties;
    @Autowired private LinerClient client;
    @Autowired private LinerPayloadMapper mapper;
    @Autowired private AiGenerationLogService logService;
    private long userId;

    @DynamicPropertySource
    static void endpoint(DynamicPropertyRegistry registry) {
        registry.add("mongle.ai.liner.endpoint", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/chat");
    }

    @BeforeEach
    void prepareCommittedUser() {
        REPLIES.clear();
        logs.deleteAll();
        users.deleteAll();
        // 로그의 REQUIRES_NEW 트랜잭션이 참조할 수 있도록 사용자는 먼저 커밋한다.
        userId = users.saveAndFlush(User.create("logging@example.com", "몽글")).getId();
    }

    @AfterAll
    static void closeProvider() {
        SERVER.stop(0);
        EXECUTOR.shutdownNow();
    }

    @Test
    void savesEachRetryWithSharedCallIdAndPricesWithoutDoubleCounting() {
        REPLIES.add(new Reply(429, "{\"error\":{\"retryable\":true}}", Map.of("x-request-id", "limited"), Duration.ZERO));
        REPLIES.add(reply(success("완료", "liner-mark-1.1", completeUsage(), "stop"), "success"));
        var result = await(gateway.generate(request(userId)));
        var attempts = attempts();

        assertThat(result.content()).isEqualTo("완료");
        assertThat(attempts).hasSize(2);
        var failed = attempts.get(0);
        var succeeded = attempts.get(1);
        assertThat(failed.getCallId()).isEqualTo(succeeded.getCallId()).hasSize(36);
        assertThat(failed.getAttemptNo()).isEqualTo(1);
        assertThat(succeeded.getAttemptNo()).isEqualTo(2);
        assertThat(failed.isSuccess()).isFalse();
        assertThat(failed.getHttpStatus()).isEqualTo(429);
        assertThat(failed.getRequestId()).isEqualTo("limited");
        assertThat(failed.getErrorCode()).isEqualTo("AI_503_2");
        assertThat(failed.getActualCost()).isNull();
        assertThat(failed.getInputTokens()).isNull();
        assertThat(succeeded.isSuccess()).isTrue();
        assertThat(succeeded.getHttpAttempted()).isTrue();
        assertThat(succeeded.getHttpStatus()).isEqualTo(200);
        assertThat(succeeded.getProvider()).isEqualTo("liner");
        assertThat(succeeded.getRequestedModel()).isEqualTo("liner-mark-1.1");
        assertThat(succeeded.getModelName()).isEqualTo("liner-mark-1.1");
        assertThat(succeeded.getPromptVersion()).isEqualTo("logging-v1");
        assertThat(succeeded.getTaskType()).isEqualTo(AiTaskType.DREAM_STRUCTURE);
        assertThat(succeeded.getUser().getId()).isEqualTo(userId);
        assertThat(succeeded.getRequestId()).isEqualTo("success");
        assertThat(succeeded.getInputTokens()).isEqualTo(1000);
        assertThat(succeeded.getOutputTokens()).isEqualTo(200);
        assertThat(succeeded.getTotalTokens()).isEqualTo(1200);
        assertThat(succeeded.getCachedInputTokens()).isEqualTo(250);
        assertThat(succeeded.getReasoningTokens()).isEqualTo(50);
        assertThat(succeeded.getFinishReason()).isEqualTo("stop");
        assertThat(succeeded.getErrorCode()).isNull();
        assertThat(succeeded.getCostStatus()).isEqualTo(AiCostStatus.CALCULATED);
        assertThat(succeeded.getActualCost()).isEqualByComparingTo("0.00177500");
        assertThat(succeeded.getCostCurrency()).isEqualTo("USD");
        assertThat(succeeded.getPricingModel()).isEqualTo("liner-mark-1.1");
        assertThat(succeeded.getPricingVersion()).isEqualTo("2026-10-08");
        assertThat(succeeded.getInputPrice()).isEqualByComparingTo("1.00");
        assertThat(succeeded.getOutputPrice()).isEqualByComparingTo("5.00");
        assertThat(succeeded.getCachedInputPrice()).isEqualByComparingTo("0.10");
        assertThat(succeeded.getBaselineCost()).isNull();
        assertThat(succeeded.getCreatedAt()).isNotNull();
        assertThat(succeeded.getLatencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(failed.getLatencyMs() + succeeded.getLatencyMs());
    }

    @Test
    void recordsKnownUsageEvenWhenHttp200ContentCannotBeReturned() {
        REPLIES.add(reply(success("", "liner-mark-1.1", completeUsage(), "stop"), "bad-content"));
        assertThatThrownBy(() -> await(gateway.generate(request(userId)))).isInstanceOfSatisfying(AiGatewayException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(AiGatewayErrorCode.INVALID_RESPONSE));
        var failed = attempts().getFirst();
        assertThat(failed.isSuccess()).isFalse();
        assertThat(failed.getHttpStatus()).isEqualTo(200);
        assertThat(failed.getErrorCode()).isEqualTo("AI_502_3");
        assertThat(failed.getRequestId()).isEqualTo("bad-content");
        assertThat(failed.getInputTokens()).isEqualTo(1000);
        assertThat(failed.getCostStatus()).isEqualTo(AiCostStatus.CALCULATED);
        assertThat(failed.getActualCost()).isEqualByComparingTo("0.00177500");
        assertThat(attempts()).hasSize(1);
    }

    @Test
    void distinguishesMissingUsageMissingPriceAndReportedZero() {
        REPLIES.add(reply(success("부분 사용량", "liner-mark-1.1",
                "{\"prompt_tokens\":10,\"completion_tokens\":20}", "stop"), "partial"));
        REPLIES.add(reply(success("미등록 모델", "unknown-model", completeUsage(), "stop"), "unknown"));
        REPLIES.add(reply(success("모델 누락", null, completeUsage(), "stop"), "missing-model"));
        REPLIES.add(reply(success("실제 0", "liner-mark-1.1", """
                {"prompt_tokens":0,"completion_tokens":0,"total_tokens":0,
                 "prompt_tokens_details":{"cached_tokens":0},"completion_tokens_details":{"reasoning_tokens":0}}
                """, "stop"), "zero"));
        for (int i = 0; i < 4; i++) await(gateway.generate(request(userId)));
        var rows = attempts();
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).getCostStatus()).isEqualTo(AiCostStatus.USAGE_MISSING);
        assertThat(rows.get(0).getInputTokens()).isEqualTo(10);
        assertThat(rows.get(0).getCachedInputTokens()).isNull();
        assertThat(rows.get(0).getTotalTokens()).isNull();
        assertThat(rows.get(0).getActualCost()).isNull();
        assertThat(rows.get(0).getPricingVersion()).isNotNull();
        assertThat(rows.get(1).getCostStatus()).isEqualTo(AiCostStatus.PRICE_MISSING);
        assertThat(rows.get(1).getModelName()).isEqualTo("unknown-model");
        assertThat(rows.get(1).getInputTokens()).isEqualTo(1000);
        assertThat(rows.get(1).getActualCost()).isNull();
        assertThat(rows.get(2).getModelName()).isNull();
        assertThat(rows.get(2).getCostStatus()).isEqualTo(AiCostStatus.PRICE_MISSING);
        assertThat(rows.get(2).getActualCost()).isNull();
        assertThat(rows.get(3).getCostStatus()).isEqualTo(AiCostStatus.CALCULATED);
        assertThat(rows.get(3).getInputTokens()).isZero();
        assertThat(rows.get(3).getActualCost()).isEqualByComparingTo("0");
    }

    @Test
    void failedLogTransactionDoesNotReplaceResultOrRollBackBusinessTransaction() {
        REPLIES.add(reply(success("반환 유지", "liner-mark-1.1", completeUsage(), "stop"), "fk-failure"));
        var transaction = new TransactionTemplate(transactionManager);
        Long businessUserId = transaction.execute(status -> {
            var businessUser = users.saveAndFlush(User.create("business@example.com", "몽글"));
            // 의도적으로 없는 사용자 FK를 줘 내부 로그 트랜잭션의 실제 DB 오류를 일으킨다.
            assertThat(await(gateway.generate(request(Long.MAX_VALUE))).content()).isEqualTo("반환 유지");
            return businessUser.getId();
        });
        assertThat(users.findById(businessUserId)).isPresent();
        assertThat(logs.count()).isZero();
    }

    @Test
    void failedLogTransactionDoesNotReplaceProviderException() {
        REPLIES.add(new Reply(401, "{\"error\":{\"message\":\"외부 민감 정보\"}}", Map.of("x-request-id", "auth-failure"), Duration.ZERO));
        assertThatThrownBy(() -> await(gateway.generate(request(Long.MAX_VALUE)))).isInstanceOfSatisfying(AiGatewayException.class, failure -> {
            assertThat(failure.getErrorCode()).isEqualTo(AiGatewayErrorCode.AUTHENTICATION_FAILED);
            assertThat(failure.getRequestId()).isEqualTo("auth-failure");
        });
        assertThat(logs.count()).isZero();
    }

    @Test
    void committedLogSurvivesLaterBusinessRollback() {
        REPLIES.add(reply(success("독립 저장", "liner-mark-1.1", completeUsage(), "stop"), "outer-rollback"));
        var transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            await(gateway.generate(request(userId)));
            users.saveAndFlush(User.create("rolled-back@example.com", "몽글"));
            throw new IllegalStateException("업무 저장 실패");
        })).hasMessage("업무 저장 실패");
        assertThat(users.count()).isEqualTo(1);
        assertThat(attempts()).hasSize(1);
        assertThat(attempts().getFirst().isSuccess()).isTrue();
    }

    @Test
    void recordsTimeoutAndPreflightFailureWithoutInventingResponseOrUsage() {
        REPLIES.add(new Reply(200, success("늦은 결과", "liner-mark-1.1", completeUsage(), "stop"), Map.of(), Duration.ofSeconds(2)));
        assertThatThrownBy(() -> await(gateway.generate(request(userId)))).isInstanceOfSatisfying(AiGatewayException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(AiGatewayErrorCode.TIMEOUT));
        var withoutKey = new LinerProperties("", properties.endpoint(), properties.model(), properties.maxCompletionTokens(),
                properties.reasoningEffort(), properties.connectTimeout(), properties.requestTimeout(), properties.totalTimeout(),
                properties.maxRetries(), properties.retryBackoff(), properties.maxRetryDelay());
        var missingKeyGateway = new LinerAiGateway(withoutKey, client, mapper, logService, resources);
        assertThatThrownBy(() -> await(missingKeyGateway.generate(request(userId)))).isInstanceOfSatisfying(AiGatewayException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(AiGatewayErrorCode.AUTHENTICATION_FAILED));
        var rows = attempts();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getErrorCode()).isEqualTo("AI_504_1");
        assertThat(rows.get(0).getHttpAttempted()).isTrue();
        assertThat(rows.get(1).getErrorCode()).isEqualTo("AI_502_2");
        assertThat(rows.get(1).getHttpAttempted()).isFalse();
        for (var row : rows) {
            assertThat(row.isSuccess()).isFalse();
            assertThat(row.getHttpStatus()).isNull();
            assertThat(row.getInputTokens()).isNull();
            assertThat(row.getActualCost()).isNull();
            assertThat(row.getAttemptNo()).isEqualTo(1);
        }
    }

    private List<AiGenerationLog> attempts() {
        return logs.findAll(Sort.by("id"));
    }

    private AiGenerationRequest request(long ownerId) {
        return new AiGenerationRequest(ownerId, AiTaskType.DREAM_STRUCTURE, "logging-v1",
                List.of(new AiMessage(AiMessage.Role.USER, "저장하지 않을 모의 입력")), null);
    }

    private static Reply reply(String body, String requestId) {
        return new Reply(200, body, Map.of("x-request-id", requestId), Duration.ZERO);
    }

    private static String success(String content, String model, String usage, String finish) {
        // 이 테스트의 내용은 따옴표가 없는 고정 문자열이다. 실제 직렬화는 기존 통신 테스트에서 검증한다.
        return "{" + (model == null ? "" : "\"model\":\"" + model + "\",") + "\"usage\":" + usage
                + ",\"choices\":[{\"finish_reason\":\"" + finish + "\",\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"}}]}";
    }

    private static String completeUsage() {
        return """
                {"prompt_tokens":1000,"completion_tokens":200,"total_tokens":1200,
                 "prompt_tokens_details":{"cached_tokens":250},"completion_tokens_details":{"reasoning_tokens":50}}
                """;
    }

    private static HttpServer startProvider() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(EXECUTOR);
            server.createContext("/chat", exchange -> {
                exchange.getRequestBody().readAllBytes();
                var reply = REPLIES.poll();
                if (reply == null) reply = new Reply(500, "{}", Map.of(), Duration.ZERO);
                var bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                reply.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
                exchange.sendResponseHeaders(reply.status(), bytes.length);
                try {
                    Thread.sleep(reply.delay());
                    exchange.getResponseBody().write(bytes);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("로컬 모의 서버를 시작할 수 없습니다.", exception);
        }
    }

    private record Reply(int status, String body, Map<String, String> headers, Duration delay) { }
}
