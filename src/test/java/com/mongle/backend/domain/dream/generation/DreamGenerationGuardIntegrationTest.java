package com.mongle.backend.domain.dream.generation;

import static com.mongle.backend.domain.dream.gateway.DreamAiTestAwait.await;
import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.auth.service.TokenService;
import com.mongle.backend.domain.dream.analysis.*;
import com.mongle.backend.domain.dream.dto.*;
import com.mongle.backend.domain.dream.dto.DreamMutationStatus.BlockingTask;
import com.mongle.backend.domain.dream.entity.DreamEmotion;
import com.mongle.backend.domain.dream.exception.DreamErrorCode;
import com.mongle.backend.domain.dream.image.*;
import com.mongle.backend.domain.dream.service.DreamService;
import com.mongle.backend.domain.dream.story.*;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.domain.world.bridge.*;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.BusinessException;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:mutation-guards;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "mongle.image.styles[0]=test-style"
})
@ActiveProfiles("test")
@Import(DreamGenerationGuardIntegrationTest.Config.class)
class DreamGenerationGuardIntegrationTest {
    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        StructureGenerator structure() {
            return input -> CompletableFuture.completedFuture("""
                    {"generatedTitle":"바다","displayKeywords":["바다"],"elements":[],
                    "scenes":[{"sequence":1,"content":"바다를 보았다","disconnectedFromPrevious":false,"elementKeys":[]}]}
                    """);
        }
        @Bean
        @Primary
        StoryGenerator story() {
            return input -> CompletableFuture.completedFuture("""
                    {"sections":[{"sequence":1,"kind":"SCENE","sceneSequence":1,"content":"바다를 보았다."}]}
                    """);
        }
    }

    @Autowired DreamService dreams;
    @Autowired DreamStructureService analyses;
    @Autowired DreamStoryService stories;
    @Autowired StoryTransactions storyTransactions;
    @Autowired ImageTransactions images;
    @Autowired BridgeTransactions bridges;
    @Autowired DreamGenerationTransactions generation;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenService tokens;
    @Value("${local.server.port}")
    int port;
    Long user;
    DreamResponse a, b, c;

    @BeforeEach
    void setup() {
        user = users.saveAndFlush(User.create(UUID.randomUUID() + "@test.com", "몽글")).getId();
        a = ready(1);
        b = ready(2);
        c = ready(3);
    }

    private DreamResponse ready(int day) {
        var d = dreams.create(user, new DreamCreateRequest(LocalDate.of(2026, 10, day), "바다를 보았다"));
        d = dreams.complete(user, d.dreamId(),
                new DreamEmotionsRequest(d.revision(), List.of(DreamEmotion.HAPPY)));
        await(analyses.analyze(user, d.dreamId(), d.revision()));
        d = dreams.get(user, d.dreamId());
        await(stories.generate(user, d.dreamId(), new StoryRequest(d.revision(), false, null)));
        // 수동으로 분석/서사까지 완료한 fixture의 자동 작업도 완료 상태로 맞춘다.
        jdbc.update("update dream_generation_jobs set status='COMPLETED',stage='STORY' where dream_id=?", d.dreamId());
        return dreams.get(user, d.dreamId());
    }

    private BridgeRequest pair(DreamResponse x, DreamResponse y) {
        return new BridgeRequest(storyTransactions.latest(user, x.dreamId()).resultVersionId(),
                storyTransactions.latest(user, y.dreamId()).resultVersionId());
    }

    private ImageRequest imageRequest(DreamResponse d) {
        return new ImageRequest(d.revision(), "test-style", null, false, null);
    }

    private void blocked(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(DreamErrorCode.GENERATION_IN_PROGRESS);
    }

    private DreamUpdateRequest edit(DreamResponse d) {
        var request = new DreamUpdateRequest();
        request.setRevision(d.revision());
        request.setTitle("새 제목");
        return request;
    }

    private void regenerate(DreamResponse d) {
        generation.regenerate(user, d.dreamId(), new DreamRegenerationRequest(d.revision(),
                generation.get(user, d.dreamId()).generationVersion()));
    }

    @Test
    void activeImageBlocksEveryEditAndDeletionOnlyForItsDreamAndFailureReleasesIt() {
        var pending = images.begin(user, a.dreamId(), imageRequest(a), true);
        assertThat(dreams.mutationStatus(user, a.dreamId()).blockingTasks()).containsExactly(BlockingTask.IMAGE);
        for (int field = 0; field < 3; field++) {
            var request = new DreamUpdateRequest();
            request.setRevision(a.revision());
            if (field == 0) request.setTitle("새 제목");
            if (field == 1) request.setOriginalText("새 원문");
            if (field == 2) request.setEmotions(List.of(DreamEmotion.SAD));
            blocked(() -> dreams.update(user, a.dreamId(), request));
        }
        blocked(() -> dreams.delete(user, a.dreamId(), a.revision()));
        assertThat(dreams.mutationStatus(user, b.dreamId()).mutable()).isTrue();
        dreams.update(user, b.dreamId(), edit(b));
        assertThat(dreams.get(user, a.dreamId()).revision()).isEqualTo(a.revision());
        images.fail(pending.input(), "CALL_FAILED");
        assertThat(dreams.mutationStatus(user, a.dreamId()).mutable()).isTrue();
        dreams.update(user, a.dreamId(), edit(a));
    }

    @Test
    void sharedBridgeKeepsBothSourcesBlockedUntilEveryActiveEdgeFinishes() {
        var ab = bridges.begin(user, pair(a, b), true);
        var bc = bridges.begin(user, pair(b, c), true);
        blocked(() -> dreams.update(user, a.dreamId(), edit(a)));
        blocked(() -> dreams.delete(user, b.dreamId(), b.revision()));
        assertThat(dreams.mutationStatus(user, c.dreamId()).blockingTasks())
                .containsExactly(BlockingTask.WORLD_BRIDGE);
        bridges.finish(ab.input(), "연결 완료");
        assertThat(dreams.mutationStatus(user, a.dreamId()).mutable()).isTrue();
        assertThat(dreams.mutationStatus(user, b.dreamId()).mutable()).isFalse();
        bridges.fail(bc.input(), "CALL_FAILED");
        assertThat(dreams.mutationStatus(user, b.dreamId()).mutable()).isTrue();
        dreams.delete(user, b.dreamId(), b.revision());
    }

    @Test
    void imageAndBridgeReasonsAreCombinedAndCompletionReleasesOnlyItsOwnReason() {
        var image = images.begin(user, a.dreamId(), imageRequest(a), true);
        var bridge = bridges.begin(user, pair(a, b), true);
        assertThat(dreams.mutationStatus(user, a.dreamId()).blockingTasks())
                .containsExactly(BlockingTask.IMAGE, BlockingTask.WORLD_BRIDGE);
        images.finish(image.input(), "test-key", new ImagePayload(new byte[]{1}, "image/png", 1, 1));
        assertThat(dreams.mutationStatus(user, a.dreamId()).blockingTasks())
                .containsExactly(BlockingTask.WORLD_BRIDGE);
        bridges.finish(bridge.input(), "완료");
        assertThat(dreams.mutationStatus(user, a.dreamId()).mutable()).isTrue();
    }

    @Test
    void expiredAttemptsUnlockEditingAndTheirLateSuccessCannotBePublished() {
        var image = images.begin(user, a.dreamId(), imageRequest(a), true);
        var bridge = bridges.begin(user, pair(a, b), true);
        jdbc.update("update dream_images set lease_until=TIMESTAMP '2000-01-01 00:00:00' where id=?",
                image.response().imageId());
        jdbc.update("update world_bridge_results set lease_until=TIMESTAMP '2000-01-01 00:00:00' where id=?",
                bridge.response().bridgeId());
        assertThat(dreams.mutationStatus(user, a.dreamId()).mutable()).isTrue();
        dreams.update(user, a.dreamId(), edit(a));
        var late = images.finish(image.input(), "late-key", new ImagePayload(new byte[]{1}, "image/png", 1, 1));
        assertThat(late.stored()).isFalse();
        assertThat(late.response().failureCode()).isEqualTo("ATTEMPT_EXPIRED");
        assertThat(bridges.finish(bridge.input(), "늦은 내용").content()).isNull();
        assertThat(dreams.mutationStatus(user, a.dreamId()).mutable()).isTrue();
    }

    @Test
    void regenerationAndLegacyStoryApiCannotReplaceNarrativeDuringDerivedWork() {
        var image = images.begin(user, a.dreamId(), imageRequest(a), true);
        blocked(() -> regenerate(a));
        var story = storyTransactions.latest(user, a.dreamId());
        blocked(() -> storyTransactions.begin(user, a.dreamId(),
                new StoryRequest(a.revision(), true, story.storyVersion()), true));
        images.fail(image.input(), "CALL_FAILED");
        var bridge = bridges.begin(user, pair(a, b), true);
        blocked(() -> regenerate(a));
        blocked(() -> regenerate(b));
        bridges.fail(bridge.input(), "CALL_FAILED");
        regenerate(a);
        assertThat(dreams.mutationStatus(user, a.dreamId()).blockingTasks())
                .containsExactly(BlockingTask.ANALYSIS_STORY);
    }

    @Test
    void newDerivedWorkIsRejectedDuringRegenerationButSuccessfulCachesRemainReadable() {
        var pair = pair(a, b);
        var bridge = bridges.begin(user, pair, true);
        bridges.finish(bridge.input(), "캐시");
        var image = images.begin(user, a.dreamId(), imageRequest(a), true);
        images.finish(image.input(), "existing", new ImagePayload(new byte[]{1}, "image/png", 1, 1));
        regenerate(a);
        assertThat(bridges.begin(user, pair, false).input()).isNull();
        assertThat(images.begin(user, a.dreamId(), imageRequest(a), false).input()).isNull();
        blocked(() -> bridges.begin(user, pair(a, c), true));
        var currentImage = images.get(user, image.response().imageId());
        blocked(() -> images.begin(user, a.dreamId(),
                new ImageRequest(a.revision(), "test-style", null, true, currentImage.imageVersion()), true));
        assertThat(bridges.get(user, bridge.response().bridgeId()).content()).isEqualTo("캐시");
    }

    @Test
    void failedGenerationRetryCannotBypassActiveDerivedWork() {
        jdbc.update("update dream_generation_jobs set status='FAILED' where dream_id=?", a.dreamId());
        var bridge = bridges.begin(user, pair(a, b), true);
        blocked(() -> generation.retry(user, a.dreamId(), a.revision()));
        assertThat(generation.get(user, a.dreamId()).status()).isEqualTo(DreamGenerationJob.Status.FAILED);
        bridges.fail(bridge.input(), "CALL_FAILED");
        assertThat(generation.retry(user, a.dreamId(), a.revision()).status())
                .isEqualTo(DreamGenerationJob.Status.QUEUED);
    }

    @Test
    void failedDerivedRetriesCannotBypassQueuedRegeneration() {
        var pair = pair(a, b);
        var bridge = bridges.begin(user, pair, true);
        var failedBridge = bridges.fail(bridge.input(), "CALL_FAILED");
        var image = images.begin(user, a.dreamId(), imageRequest(a), true);
        var failedImage = images.fail(image.input(), "CALL_FAILED");
        regenerate(a);
        blocked(() -> bridges.retry(user, failedBridge.bridgeId(), failedBridge.jobVersion(), true));
        blocked(() -> images.begin(user, a.dreamId(), imageRequest(a), true));
        assertThat(bridges.begin(user, pair, false).input()).isNull();
        assertThat(bridges.get(user, failedBridge.bridgeId())).isEqualTo(failedBridge);
        assertThat(images.get(user, failedImage.imageId())).isEqualTo(failedImage);
    }

    @Test
    void mutationStatusApiChecksAuthOwnershipNoStoreAndActualWriteRejects() throws Exception {
        var image = images.begin(user, a.dreamId(), imageRequest(a), true);
        var access = tokens.login(user).response().accessToken();
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@test.com", "타인"));
        var stranger = tokens.login(other.getId()).response().accessToken();
        String path = "/api/v1/dreams/" + a.dreamId();
        try (var client = HttpClient.newHttpClient()) {
            assertThat(send(client, path + "/mutation-status", null, "GET", null).statusCode()).isEqualTo(401);
            assertThat(send(client, path + "/mutation-status", stranger, "GET", null).statusCode()).isEqualTo(404);
            assertThat(send(client, "/api/v1/dreams/9999999/mutation-status", access, "GET", null).statusCode()).isEqualTo(404);
            var status = send(client, path + "/mutation-status", access, "GET", null);
            assertThat(status.statusCode()).isEqualTo(200);
            assertThat(status.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(status.body()).contains("\"mutable\":false", "IMAGE");
            var edit = send(client, path, access, "PATCH",
                    "{\"revision\":" + a.revision() + ",\"title\":\"수정\"}");
            assertThat(edit.statusCode()).isEqualTo(409);
            assertThat(edit.body()).contains("DREAM_409_4");
            assertThat(send(client, path + "?revision=" + a.revision(), access, "DELETE", null).statusCode())
                    .isEqualTo(409);
            var swagger = send(client, "/v3/api-docs", null, "GET", null);
            assertThat(swagger.body()).contains("/api/v1/dreams/{dreamId}/mutation-status", "blockingTasks");
        }
        images.fail(image.input(), "CALL_FAILED");
    }

    @Test
    void concurrentImageReservationAndEditCannotBothSucceed() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var image = executor.submit(() -> outcome(start,
                    () -> images.begin(user, a.dreamId(), imageRequest(a), true)));
            var edit = executor.submit(() -> outcome(start,
                    () -> dreams.update(user, a.dreamId(), edit(a))));
            start.countDown();
            assertThat(List.of(image.get(5, TimeUnit.SECONDS), edit.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    @Test
    void concurrentBridgeReservationAndDeleteCannotBothSucceed() throws Exception {
        var pair = pair(a, b);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var bridge = executor.submit(() -> outcome(start, () -> bridges.begin(user, pair, true)));
            var delete = executor.submit(() -> outcome(start,
                    () -> dreams.delete(user, a.dreamId(), a.revision())));
            start.countDown();
            assertThat(List.of(bridge.get(5, TimeUnit.SECONDS), delete.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    private boolean outcome(CountDownLatch start, Runnable action) throws Exception {
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            action.run();
            return true;
        } catch (BusinessException ex) {
            assertThat(ex.getErrorCode().getHttpStatus().is4xxClientError()).isTrue();
            return false;
        }
    }

    private HttpResponse<String> send(HttpClient client, String path, String token, String method, String body)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
