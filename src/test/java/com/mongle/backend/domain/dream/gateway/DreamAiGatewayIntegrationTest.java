package com.mongle.backend.domain.dream.gateway;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.ai.gateway.AiGateway;
import com.mongle.backend.domain.ai.liner.LinerAiGateway;
import com.mongle.backend.domain.auth.service.TokenService;
import com.mongle.backend.domain.dream.dto.*;
import com.mongle.backend.domain.dream.entity.DreamEmotion;
import com.mongle.backend.domain.dream.service.DreamService;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.sun.net.httpserver.HttpServer;

import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 실제 MVC → 생성기 → LINER HTTP → 검증·DB 저장을 유료 호출 없이 확인한다. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:mongle-real-gateway;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "mongle.ai.liner.api-key=test-key",
            "mongle.ai.liner.connect-timeout=1s",
            "mongle.ai.liner.request-timeout=5s",
            "mongle.ai.liner.total-timeout=6s",
            "mongle.ai.liner.max-retries=0",
            "spring.threads.virtual.enabled=false",
            "mongle.dream.ai.max-concurrent-calls=1"
        })
@ActiveProfiles("test")
@Import(DreamAiGatewayIntegrationTest.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DreamAiGatewayIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Queue<Reply> REPLIES = new ConcurrentLinkedQueue<>();
    private static final Queue<JsonNode> RECEIVED = new ConcurrentLinkedQueue<>();
    private static final Queue<CallContext> CALLS = new ConcurrentLinkedQueue<>();
    private static HttpServer provider;
    private static ExecutorService providerExecutor;
    private static final String STRUCTURE =
            """
{"generatedTitle":"바다에서 숲으로","displayKeywords":["바다","숲길"],
"elements":[{"key":"sea","type":"PLACE","name":"바다","description":"꿈에 나온 바다"}],"scenes":[
  {"sequence":1,"content":"바다 위를 날았다","disconnectedFromPrevious":false,"elementKeys":["sea"]},
  {"sequence":2,"content":"숲길을 걸었다","disconnectedFromPrevious":true,"elementKeys":[]}
]}
""";
    private static final String STORY =
            """
            {"sections":[
              {"sequence":1,"kind":"SCENE","sceneSequence":1,"content":"바다 위를 날았다."},
              {"sequence":2,"kind":"SCENE","sceneSequence":2,"content":"숲길을 걸었다."}
            ]}
            """;

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        AiGateway observedGateway(LinerAiGateway liner, EntityManagerFactory entityManagerFactory) {
            return request -> {
                CALLS.add(
                        new CallContext(
                                Thread.currentThread().isVirtual(),
                                TransactionSynchronizationManager.isActualTransactionActive(),
                                TransactionSynchronizationManager.hasResource(
                                        entityManagerFactory)));
                return liner.generate(request);
            };
        }
    }

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) throws Exception {
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        providerExecutor = Executors.newVirtualThreadPerTaskExecutor();
        provider.setExecutor(providerExecutor);
        provider.createContext(
                "/chat/completions",
                exchange -> {
                    try {
                        RECEIVED.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
                        var reply = REPLIES.poll();
                        if (reply == null) throw new IllegalStateException("예정하지 않은 AI 호출");
                        if (reply.entered() != null) {
                            reply.entered().countDown();
                            if (!reply.release().await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("모의 응답 해제 시간 초과");
                            }
                        }
                        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(reply.status(), body.length);
                        exchange.getResponseBody().write(body);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        provider.start();
        registry.add(
                "mongle.ai.liner.endpoint",
                () -> "http://127.0.0.1:" + provider.getAddress().getPort() + "/chat/completions");
    }

    @AfterAll
    static void stopProvider() {
        if (provider != null) provider.stop(0);
        if (providerExecutor != null) providerExecutor.shutdownNow();
    }

    @Value("${local.server.port}")
    int port;

    @Autowired DreamService dreams;
    @Autowired UserRepository users;
    @Autowired TokenService tokens;
    private Long userId;
    private String token;
    private HttpClient client;

    @BeforeEach
    void setUp() {
        REPLIES.clear();
        RECEIVED.clear();
        CALLS.clear();
        userId = users.saveAndFlush(User.create(UUID.randomUUID() + "@test.com", "테스터")).getId();
        token = tokens.login(userId).response().accessToken();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stopClient() {
        client.shutdownNow();
    }

    @Test
    void storesBothOutputsAndReusesResultsWithoutAnotherProviderCall() throws Exception {
        var dream = analyzedDream();
        REPLIES.add(success(STORY));
        var story = result(post(dream, "story"), 200);

        assertThat(story.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(story.path("sections").size()).isEqualTo(2);
        assertThat(result(post(dream, "story"), 200)).isEqualTo(story);
        assertThat(result(post(dream, "analysis"), 200).path("scenes").size()).isEqualTo(2);
        assertThat(RECEIVED).hasSize(2);
        var requests = List.copyOf(RECEIVED);
        assertThat(requests.get(0).at("/response_format/json_schema/name").asString())
                .isEqualTo("dream_structure");
        assertThat(requests.get(1).at("/response_format/json_schema/name").asString())
                .isEqualTo("dream_story");
        for (var request : requests) {
            assertThat(request.at("/response_format/type").asString()).isEqualTo("json_schema");
            assertThat(request.at("/response_format/json_schema/strict").asBoolean()).isTrue();
            assertThat(request.toString())
                    .doesNotContain("userId", "attemptId", "analysisId", "storyId");
        }
        var data = JSON.readTree(requests.get(1).at("/messages/1/content").asString());
        assertThat(data.path("scenes").size()).isEqualTo(2);
        assertThat(data.at("/elements/0/key").asString()).isEqualTo("element_1");
        assertThat(data.at("/scenes/0/elementKeys/0").asString()).isEqualTo("element_1");
        assertThat(dreams.get(userId, dream.dreamId()).originalText())
                .isEqualTo("바다 위를 날다가 숲길을 걸었다");
        assertExternalCallContext();
    }

    @ParameterizedTest
    @CsvSource({
        "analysis, 500, CALL_FAILED", "analysis, 200, INVALID_OUTPUT",
        "story, 500, CALL_FAILED", "story, 200, INVALID_OUTPUT"
    })
    void failureKeepsSavedDreamAndAllowsRetry(String kind, int status, String code)
            throws Exception {
        var dream = readyDream(kind);
        REPLIES.add(
                status == 200
                        ? success("{\"unexpected\":true}")
                        : new Reply(status, "{}", null, null));
        var failed = result(post(dream, kind), 200);

        assertThat(failed.path("status").asString()).isEqualTo("FAILED");
        assertThat(failed.path("failureCode").asString()).isEqualTo(code);
        assertThat(failed.path(kind.equals("analysis") ? "scenes" : "sections").size()).isZero();
        assertThat(dreams.get(userId, dream.dreamId()).originalText())
                .isEqualTo(dream.originalText());
        REPLIES.add(success(output(kind)));
        var retried = result(post(dream, kind), 200);
        String id = kind.equals("analysis") ? "analysisId" : "storyId";
        assertThat(retried.path(id)).isEqualTo(failed.path(id));
        assertThat(retried.path("status").asString()).isEqualTo("COMPLETED");
        assertExternalCallContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"analysis", "story"})
    void duplicateInFlightReturnsProcessingWithoutExtraCall(String kind) throws Exception {
        var dream = readyDream(kind);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        REPLIES.add(blocked(kind, entered, release));
        int before = RECEIVED.size();
        var pending = client.sendAsync(request(dream, kind), HttpResponse.BodyHandlers.ofString());
        try {
            assertThat(entered.await(4, TimeUnit.SECONDS)).isTrue();
            assertThat(result(post(dream, kind), 202).path("status").asString())
                    .isEqualTo("PROCESSING");
            assertThat(RECEIVED).hasSize(before + 1);
        } finally {
            release.countDown();
        }
        assertThat(result(pending.get(10, TimeUnit.SECONDS), 200).path("status").asString())
                .isEqualTo("COMPLETED");
        assertExternalCallContext();
    }

    @ParameterizedTest
    @CsvSource({"analysis, false", "analysis, true", "story, false", "story, true"})
    void sourceEditOrDeleteDuringProviderWaitDiscardsOutput(String kind, boolean delete)
            throws Exception {
        var dream = readyDream(kind);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        REPLIES.add(blocked(kind, entered, release));
        var pending = client.sendAsync(request(dream, kind), HttpResponse.BodyHandlers.ofString());
        try {
            assertThat(entered.await(4, TimeUnit.SECONDS)).isTrue();
            // 같은 사용자의 행 잠금을 AI 호출 동안 유지하면 이 변경이 완료되지 못한다.
            if (delete) {
                dreams.delete(userId, dream.dreamId(), dream.revision());
            } else {
                var edit = new DreamUpdateRequest();
                edit.setRevision(dream.revision());
                edit.setOriginalText("수정한 꿈 원문");
                dreams.update(userId, dream.dreamId(), edit);
            }
        } finally {
            release.countDown();
        }
        var failed = result(pending.get(10, TimeUnit.SECONDS), 200);
        assertThat(failed.path("status").asString()).isEqualTo("FAILED");
        assertThat(failed.path("failureCode").asString())
                .isEqualTo(delete ? "SOURCE_DELETED" : "SOURCE_CHANGED");
        assertThat(failed.path(kind.equals("analysis") ? "scenes" : "sections").size()).isZero();
        assertExternalCallContext();
    }

    @Test
    void structureAndStoryShareLimitAndRejectedAttemptCanRetry() throws Exception {
        var storyDream = analyzedDream();
        var otherUser =
                users.saveAndFlush(User.create(UUID.randomUUID() + "@test.com", "다른사용자")).getId();
        var analysisDream = completed(otherUser);
        String otherToken = tokens.login(otherUser).response().accessToken();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        REPLIES.add(blocked("analysis", entered, release));
        int before = RECEIVED.size();
        var pending =
                client.sendAsync(
                        request(analysisDream, "analysis", otherToken),
                        HttpResponse.BodyHandlers.ofString());
        try {
            assertThat(entered.await(4, TimeUnit.SECONDS)).isTrue();
            var rejected = result(post(storyDream, "story"), 200);
            assertThat(rejected.path("status").asString()).isEqualTo("FAILED");
            assertThat(rejected.path("failureCode").asString()).isEqualTo("CALL_FAILED");
            assertThat(RECEIVED).hasSize(before + 1);
        } finally {
            release.countDown();
        }
        assertThat(result(pending.get(10, TimeUnit.SECONDS), 200).path("status").asString())
                .isEqualTo("COMPLETED");
        REPLIES.add(success(STORY));
        assertThat(result(post(storyDream, "story"), 200).path("status").asString())
                .isEqualTo("COMPLETED");
        assertExternalCallContext();
    }

    private DreamResponse completed(Long owner) {
        var dream =
                dreams.create(
                        owner,
                        new DreamCreateRequest(
                                LocalDate.now(ZoneId.of("Asia/Seoul")), "바다 위를 날다가 숲길을 걸었다"));
        return dreams.complete(
                owner,
                dream.dreamId(),
                new DreamEmotionsRequest(dream.revision(), List.of(DreamEmotion.HAPPY)));
    }

    private DreamResponse analyzedDream() throws Exception {
        var dream = completed(userId);
        REPLIES.add(success(STRUCTURE));
        var analysis = result(post(dream, "analysis"), 200);
        assertThat(analysis.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(analysis.path("generatedTitle").asString()).isEqualTo("바다에서 숲으로");
        return dreams.get(userId, dream.dreamId());
    }

    private DreamResponse readyDream(String kind) throws Exception {
        return kind.equals("story") ? analyzedDream() : completed(userId);
    }

    private HttpRequest request(DreamResponse dream, String kind) {
        return request(dream, kind, token);
    }

    private HttpRequest request(DreamResponse dream, String kind, String accessToken) {
        return HttpRequest.newBuilder(
                        URI.create(
                                "http://localhost:"
                                        + port
                                        + "/api/v1/dreams/"
                                        + dream.dreamId()
                                        + "/"
                                        + kind))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(
                        HttpRequest.BodyPublishers.ofString(
                                "{\"revision\":" + dream.revision() + "}"))
                .build();
    }

    private HttpResponse<String> post(DreamResponse dream, String kind) throws Exception {
        return client.send(request(dream, kind), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode result(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        return JSON.readTree(response.body()).path("data");
    }

    private void assertExternalCallContext() {
        assertThat(CALLS)
                .isNotEmpty()
                .allSatisfy(
                        context -> {
                            assertThat(context.virtual()).isFalse();
                            assertThat(context.transaction()).isFalse();
                            assertThat(context.entityManagerBound()).isFalse();
                        });
    }

    private String output(String kind) {
        return kind.equals("analysis") ? STRUCTURE : STORY;
    }

    private Reply blocked(String kind, CountDownLatch entered, CountDownLatch release) {
        return new Reply(200, success(output(kind)).body(), entered, release);
    }

    private static Reply success(String content) {
        String body =
                JSON.writeValueAsString(
                        Map.of(
                                "choices",
                                List.of(
                                        Map.of(
                                                "message",
                                                Map.of("role", "assistant", "content", content),
                                                "finish_reason",
                                                "stop"))));
        return new Reply(200, body, null, null);
    }

    private record Reply(int status, String body, CountDownLatch entered, CountDownLatch release) {}

    private record CallContext(boolean virtual, boolean transaction, boolean entityManagerBound) {}
}
