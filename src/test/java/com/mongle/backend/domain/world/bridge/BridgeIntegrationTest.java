package com.mongle.backend.domain.world.bridge;

import static com.mongle.backend.domain.dream.gateway.DreamAiTestAwait.await;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.ai.liner.config.LinerProperties;
import com.mongle.backend.domain.dream.analysis.DreamAnalysis;
import com.mongle.backend.domain.dream.dto.DreamUpdateRequest;
import com.mongle.backend.domain.dream.entity.Dream;
import com.mongle.backend.domain.dream.entity.DreamEmotion;
import com.mongle.backend.domain.dream.gateway.DreamGenerationSettings;
import com.mongle.backend.domain.dream.service.DreamService;
import com.mongle.backend.domain.dream.story.*;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.BusinessException;
import com.mongle.backend.global.error.ErrorCode;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

@SpringBootTest(
        properties =
                "spring.datasource.url=jdbc:h2:mem:bridge-tests;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
@Import(BridgeIntegrationTest.Config.class)
class BridgeIntegrationTest {
    @Autowired BridgeService service;
    @Autowired BridgeTransactions transactions;
    @Autowired DreamService dreams;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeGenerator generator;
    @Autowired MutableSettings settings;
    private Long userId;
    private Source a;
    private Source b;

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeGenerator bridgeTestGenerator() {
            return new FakeGenerator();
        }

        @Bean
        @Primary
        MutableSettings bridgeTestSettings(LinerProperties properties) {
            return new MutableSettings(properties);
        }
    }

    static class MutableSettings extends DreamGenerationSettings {
        volatile String model = "liner-mark-1.1";

        MutableSettings(LinerProperties properties) {
            super(properties);
        }

        @Override
        public String capture() {
            return super.capture().replace("liner-mark-1.1", model);
        }
    }

    static class FakeGenerator implements BridgeGenerator {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean enabled = true;
        volatile Input last;
        volatile Function<Input, CompletableFuture<String>> action;

        @Override
        public boolean available() {
            return enabled;
        }

        @Override
        public CompletableFuture<String> generate(Input input) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            last = input;
            calls.incrementAndGet();
            return action.apply(input);
        }
    }

    record Source(Long dreamId, Long storyId, Long versionId) {}

    @BeforeEach
    void setup() {
        generator.calls.set(0);
        generator.enabled = true;
        generator.action =
                input ->
                        CompletableFuture.completedFuture(
                                "{\"content\":\"그 풍경을 지나 다음 장면으로 향했다.\"}");
        settings.model = "liner-mark-1.1";
        userId =
                new TransactionTemplate(manager)
                        .execute(
                                status -> {
                                    var user = User.create(UUID.randomUUID() + "@test.com", "몽글");
                                    em.persist(user);
                                    return user.getId();
                                });
        a = source(userId, LocalDate.of(2026, 10, 1), "바다를 바라보았다.");
        b = source(userId, LocalDate.of(2026, 10, 2), "숲길을 걸었다.");
    }

    @Test
    void normalizesDateOrderAndReusesResultWithoutRequiringGateway() {
        var first =
                await(service.generate(userId, new BridgeRequest(b.versionId(), a.versionId())));
        assertThat(first.status()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(first.beforeDreamId()).isEqualTo(a.dreamId());
        assertThat(first.afterDreamId()).isEqualTo(b.dreamId());
        assertThat(generator.last.beforeStory()).isEqualTo("바다를 바라보았다.");
        assertThat(generator.last.afterStory()).isEqualTo("숲길을 걸었다.");
        generator.enabled = false;
        assertThat(await(service.generate(userId, pair()))).isEqualTo(first);
        assertThat(service.get(userId, first.bridgeId())).isEqualTo(first);
        assertThat(generator.calls).hasValue(1);
    }

    @Test
    void rejectsInvalidPairsForeignVersionsDraftsAndMissingGateway() {
        assertError(() -> service.generate(userId, null), BridgeErrorCode.INVALID_PAIR);
        assertError(
                () -> service.generate(userId, new BridgeRequest(-1L, b.versionId())),
                BridgeErrorCode.INVALID_PAIR);
        assertError(
                () -> service.generate(userId, new BridgeRequest(a.versionId(), a.versionId())),
                BridgeErrorCode.INVALID_PAIR);
        assertError(
                () -> service.generate(userId, new BridgeRequest(Long.MAX_VALUE, b.versionId())),
                BridgeErrorCode.NOT_FOUND);
        var foreignId =
                new TransactionTemplate(manager)
                        .execute(
                                status -> {
                                    var user =
                                            User.create(UUID.randomUUID() + "@test.com", "다른사용자");
                                    em.persist(user);
                                    return user.getId();
                                });
        var foreign = source(foreignId, LocalDate.of(2026, 10, 3), "타인 서사");
        assertError(
                () ->
                        service.generate(
                                userId, new BridgeRequest(a.versionId(), foreign.versionId())),
                BridgeErrorCode.NOT_FOUND);
        jdbc.update("update dreams set record_status='DRAFT' where id=?", a.dreamId());
        assertError(() -> service.generate(userId, pair()), BridgeErrorCode.INVALID_PAIR);
        jdbc.update("update dreams set record_status='COMPLETED' where id=?", a.dreamId());
        generator.enabled = false;
        assertError(() -> service.generate(userId, pair()), BridgeErrorCode.UNAVAILABLE);
        assertThat(generator.calls).hasValue(0);
    }

    @Test
    void newStoryVersionAndModelCreateDifferentKeysWhileOldResultRemainsImmutable() {
        var old = await(service.generate(userId, pair()));
        var updated = appendVersion(a, "새로운 바다 서사");
        var next =
                await(
                        service.generate(
                                userId, new BridgeRequest(updated.versionId(), b.versionId())));
        assertThat(next.bridgeId()).isNotEqualTo(old.bridgeId());
        settings.model = "another-model";
        var configured = await(service.generate(userId, pair()));
        assertThat(configured.bridgeId()).isNotIn(old.bridgeId(), next.bridgeId());
        assertThat(configured.settings().requestedModel()).isEqualTo("another-model");
        assertThat(service.get(userId, old.bridgeId())).isEqualTo(old);
        assertThat(generator.calls).hasValue(3);
    }

    @Test
    void editingLiveDreamDuringGenerationKeepsSelectedImmutableNarrative() {
        var pending = new CompletableFuture<String>();
        generator.action = input -> pending;
        var result = service.generate(userId, pair());
        var edit = new DreamUpdateRequest();
        edit.setRevision(dreams.get(userId, a.dreamId()).revision());
        edit.setOriginalText("수정한 원문");
        dreams.update(userId, a.dreamId(), edit);
        pending.complete("{\"content\":\"기존 서사 사이 연결\"}");
        assertThat(await(result).status()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(generator.last.beforeStory()).isEqualTo("바다를 바라보았다.");
        assertThat(await(service.generate(userId, pair())).bridgeId())
                .isEqualTo(await(result).bridgeId());
        assertThat(generator.calls).hasValue(1);
    }

    @Test
    void concurrentDuplicateCreatesOneReservationAndOneExternalCall() throws Exception {
        var pending = new CompletableFuture<String>();
        generator.action = input -> pending;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<CompletableFuture<BridgeResponse>> call =
                    () -> {
                        assertThat(gate.await(5, TimeUnit.SECONDS)).isTrue();
                        return service.generate(userId, pair());
                    };
            var first = executor.submit(call);
            var second = executor.submit(call);
            gate.countDown();
            var r1 = first.get(10, TimeUnit.SECONDS);
            var r2 = second.get(10, TimeUnit.SECONDS);
            var reused = await(r1.isDone() ? r1 : r2);
            assertThat(reused.status()).isEqualTo(GenerationStatus.PROCESSING);
            pending.complete("{\"content\":\"연결 완료\"}");
            assertThat(await(r1).bridgeId()).isEqualTo(await(r2).bridgeId());
            assertThat(generator.calls).hasValue(1);
        } finally {
            pending.complete("{\"content\":\"연결 완료\"}");
        }
    }

    @Test
    void invalidOutputRequiresExplicitVersionedRetryAndChangedSettingsCannotReuseOldKey() {
        generator.action = input -> CompletableFuture.completedFuture("{}");
        var failed = await(service.generate(userId, pair()));
        assertThat(failed.failureCode()).isEqualTo("INVALID_OUTPUT");
        assertThat(failed.content()).isNull();
        assertThat(await(service.generate(userId, pair()))).isEqualTo(failed);
        assertError(
                () -> service.retry(userId, failed.bridgeId(), failed.jobVersion() - 1),
                BridgeErrorCode.VERSION_CONFLICT);
        settings.model = "another-model";
        assertError(
                () -> service.retry(userId, failed.bridgeId(), failed.jobVersion()),
                BridgeErrorCode.SETTINGS_CHANGED);
        settings.model = "liner-mark-1.1";
        generator.action = input -> CompletableFuture.completedFuture("{\"content\":\"재시도 성공\"}");
        var done = await(service.retry(userId, failed.bridgeId(), failed.jobVersion()));
        assertThat(done.status()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(done.bridgeId()).isEqualTo(failed.bridgeId());
        assertThat(await(service.retry(userId, done.bridgeId(), failed.jobVersion())))
                .isEqualTo(done);
        assertThat(generator.calls).hasValue(2);
    }

    @Test
    void expiredAttemptCannotOverwriteRetriedResult() {
        var old = transactions.begin(userId, pair(), true);
        expire(old.response().bridgeId());
        var expired = service.get(userId, old.response().bridgeId());
        assertThat(expired.failureCode()).isEqualTo("ATTEMPT_EXPIRED");
        assertThat(await(service.generate(userId, pair()))).isEqualTo(expired);
        var next = transactions.retry(userId, expired.bridgeId(), expired.jobVersion(), true);
        assertThat(next.input().attemptId()).isNotEqualTo(old.input().attemptId());
        assertThat(transactions.finish(old.input(), "늦은 결과").status())
                .isEqualTo(GenerationStatus.PROCESSING);
        var done = transactions.finish(next.input(), "새 성공 결과");
        assertThat(transactions.finish(old.input(), "늦은 결과")).isEqualTo(done);
        assertThat(transactions.fail(old.input(), "CALL_FAILED")).isEqualTo(done);
        assertThat(done.content()).isEqualTo("새 성공 결과");
        assertThat(generator.calls).hasValue(0);
    }

    @Test
    void lateCompletionWithoutRetryIsExpiredAndNeverPublished() {
        var reservation = transactions.begin(userId, pair(), true);
        expire(reservation.response().bridgeId());
        var result = transactions.finish(reservation.input(), "만료된 결과");
        assertThat(result.failureCode()).isEqualTo("ATTEMPT_EXPIRED");
        assertThat(result.content()).isNull();
    }

    @Test
    void deletionRemovesOnlyAffectedEdgesWithoutGeneratingNewAdjacentEdge() {
        var c = source(userId, LocalDate.of(2026, 10, 3), "도시 서사");
        var d = source(userId, LocalDate.of(2026, 10, 4), "하늘 서사");
        var ab = await(service.generate(userId, pair()));
        var bc = await(service.generate(userId, new BridgeRequest(b.versionId(), c.versionId())));
        var cd = await(service.generate(userId, new BridgeRequest(c.versionId(), d.versionId())));
        dreams.delete(userId, b.dreamId(), dreams.get(userId, b.dreamId()).revision());
        assertError(() -> service.get(userId, ab.bridgeId()), BridgeErrorCode.NOT_FOUND);
        assertError(() -> service.get(userId, bc.bridgeId()), BridgeErrorCode.NOT_FOUND);
        assertThat(service.get(userId, cd.bridgeId())).isEqualTo(cd);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from world_bridge_results where user_id=?",
                                Long.class,
                                userId))
                .isEqualTo(1);
        assertThat(generator.calls).hasValue(3);
    }

    @Test
    void deletionWhileBridgeIsPendingRejectsLateResponseAndDeletedSnapshots() {
        var pending = new CompletableFuture<String>();
        generator.action = input -> pending;
        var call = service.generate(userId, pair());
        var input = generator.last;
        dreams.delete(userId, a.dreamId(), dreams.get(userId, a.dreamId()).revision());
        pending.complete("{\"content\":\"삭제 후 늦은 결과\"}");
        assertError(() -> await(call), BridgeErrorCode.NOT_FOUND);
        assertError(() -> transactions.fail(input, "CALL_FAILED"), BridgeErrorCode.NOT_FOUND);
        assertError(() -> service.generate(userId, pair()), BridgeErrorCode.NOT_FOUND);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from world_bridge_results where user_id=?",
                                Long.class,
                                userId))
                .isZero();
    }

    @Test
    void cancelledCallerDoesNotDiscardEventualCommittedResult() {
        var pending = new CompletableFuture<String>();
        generator.action = input -> pending;
        var call = service.generate(userId, pair());
        Long id = generator.last.bridgeId();
        assertThat(call.cancel(true)).isTrue();
        pending.complete("{\"content\":\"저장된 연결\"}");
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () ->
                                assertThat(service.get(userId, id).status())
                                        .isEqualTo(GenerationStatus.COMPLETED));
        assertThat(service.get(userId, id).content()).isEqualTo("저장된 연결");
    }

    @Test
    void storageFailureRollsBackContentAndMarksSeparateTransactionFailed() {
        generator.action = input -> CompletableFuture.completedFuture("{\"content\":\"금지된결과\"}");
        jdbc.execute(
                "alter table world_bridge_results add constraint ck_bridge_test_content check"
                        + " (content is null or content <> '금지된결과')");
        try {
            assertError(() -> await(service.generate(userId, pair())), BridgeErrorCode.CALL_FAILED);
            var failed = service.get(userId, generator.last.bridgeId());
            assertThat(failed.failureCode()).isEqualTo("PERSISTENCE_FAILED");
            assertThat(failed.content()).isNull();
        } finally {
            jdbc.execute("alter table world_bridge_results drop constraint ck_bridge_test_content");
        }
    }

    @Test
    void synchronousAndAsynchronousGatewayFailuresRemainRetryableWithoutLeakingCause() {
        generator.action =
                input -> {
                    throw new IllegalStateException("private-provider-body");
                };
        var failed = await(service.generate(userId, pair()));
        assertThat(failed.failureCode()).isEqualTo("CALL_FAILED");
        generator.action =
                input ->
                        CompletableFuture.failedFuture(
                                new IllegalStateException("private-provider-body"));
        assertThat(
                        await(service.retry(userId, failed.bridgeId(), failed.jobVersion()))
                                .failureCode())
                .isEqualTo("CALL_FAILED");
    }

    @Test
    void sharedCapacityRejectsExtraCallWithoutSendingToProviderAndReleasesSlots() {
        var pending = new CompletableFuture<String>();
        generator.action = input -> pending;
        var calls = new ArrayList<CompletableFuture<BridgeResponse>>();
        try {
            calls.add(service.generate(userId, pair()));
            for (int i = 3; i <= 5; i++) {
                var next = source(userId, LocalDate.of(2026, 10, i), "다음 서사 " + i);
                calls.add(
                        service.generate(
                                userId, new BridgeRequest(a.versionId(), next.versionId())));
            }
            var extra = source(userId, LocalDate.of(2026, 10, 6), "초과 서사");
            var rejected =
                    await(
                            service.generate(
                                    userId, new BridgeRequest(a.versionId(), extra.versionId())));
            assertThat(rejected.failureCode()).isEqualTo("CAPACITY_EXCEEDED");
            assertThat(generator.calls).hasValue(4);
            pending.complete("{\"content\":\"저장 성공\"}");
            for (var call : calls)
                assertThat(await(call).status()).isEqualTo(GenerationStatus.COMPLETED);
            var retry = await(service.retry(userId, rejected.bridgeId(), rejected.jobVersion()));
            assertThat(retry.status()).isEqualTo(GenerationStatus.COMPLETED);
            assertThat(generator.calls).hasValue(5);
        } finally {
            pending.complete("{\"content\":\"저장 성공\"}");
        }
    }

    @Test
    void ownedLookupAndRetryConcealOtherUsersResultsAndDatabaseEnforcesReuseKey() {
        var result = await(service.generate(userId, pair()));
        assertError(
                () -> service.get(Long.MAX_VALUE, result.bridgeId()), BridgeErrorCode.NOT_FOUND);
        assertError(
                () -> service.retry(Long.MAX_VALUE, result.bridgeId(), result.jobVersion()),
                BridgeErrorCode.NOT_FOUND);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                                        insert into world_bridge_results
                                        (user_id,before_dream_id,after_dream_id,before_version_id,after_version_id,
                                         prompt_version,settings_json,settings_hash,status,attempt_id,version,created_at,updated_at)
                                        select user_id,before_dream_id,after_dream_id,before_version_id,after_version_id,
                                               prompt_version,settings_json,settings_hash,status,attempt_id,version,created_at,updated_at
                                        from world_bridge_results where id=?
                                        """,
                                        result.bridgeId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private BridgeRequest pair() {
        return new BridgeRequest(a.versionId(), b.versionId());
    }

    private Source source(Long ownerId, LocalDate date, String content) {
        return new TransactionTemplate(manager)
                .execute(
                        status -> {
                            var user = em.find(User.class, ownerId);
                            var dream = Dream.create(user, "서사에 보내지 않는 원문", date);
                            dream.complete(List.of(DreamEmotion.HAPPY));
                            em.persist(dream);
                            var analysis = DreamAnalysis.create(dream);
                            em.persist(analysis);
                            var story = DreamStory.create(analysis);
                            var version = capture(story, content);
                            em.persist(story);
                            em.persist(version);
                            return new Source(dream.getId(), story.getId(), version.getId());
                        });
    }

    private Source appendVersion(Source source, String content) {
        return new TransactionTemplate(manager)
                .execute(
                        status -> {
                            var story = em.find(DreamStory.class, source.storyId());
                            var version = capture(story, content);
                            em.persist(version);
                            return new Source(source.dreamId(), source.storyId(), version.getId());
                        });
    }

    private StoryResultVersion capture(DreamStory story, String content) {
        var dream = story.getAnalysis().getDream();
        story.start(dream.getSourceRevision(), Instant.now(), Duration.ofMinutes(2));
        story.recordSettings(settings.capture());
        String encoded =
                new StoryValidator()
                        .encode(
                                new StoryResult(
                                        List.of(
                                                new StoryResult.Section(
                                                        1, StoryResult.Kind.SCENE, 1, content))));
        story.finish(encoded);
        var input =
                new StoryGenerator.Input(
                        userId,
                        story.getId(),
                        story.getAnalysis().getId(),
                        story.getAttemptId(),
                        dream.getSourceRevision(),
                        dream.getOriginalText(),
                        Set.of(DreamEmotion.HAPPY),
                        List.of(),
                        List.of());
        return StoryResultVersion.capture(story, encoded, input, "{}");
    }

    private void expire(Long id) {
        jdbc.update(
                "update world_bridge_results set lease_until=TIMESTAMP '2000-01-01 00:00:00' where"
                        + " id=?",
                id);
    }

    private void assertError(Runnable action, ErrorCode error) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(error));
    }
}
