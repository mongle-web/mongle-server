package com.mongle.backend.domain.dream.interpretation;

import static com.mongle.backend.domain.dream.gateway.DreamAiTestAwait.await;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.dream.analysis.*;
import com.mongle.backend.domain.dream.dto.*;
import com.mongle.backend.domain.dream.entity.DreamEmotion;
import com.mongle.backend.domain.dream.service.DreamService;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.error.BusinessException;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:interpretations;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "mongle.analysis.interpretation.enabled=true"
        })
@ActiveProfiles("test")
@Import(InterpretationIntegrationTest.Config.class)
class InterpretationIntegrationTest {
    static final String STRUCTURE =
            """
{"generatedTitle":"바다의 꿈","displayKeywords":["바다"],"elements":[
  {"key":"sea","type":"PLACE","name":"바다","description":"푸른 바다"}],
"scenes":[{"sequence":1,"content":"바다 위를 날았다","disconnectedFromPrevious":false,"elementKeys":["sea"]}]}
""";

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        StructureGenerator structureGenerator() {
            return input -> CompletableFuture.completedFuture(STRUCTURE);
        }

        @Bean
        @Primary
        FakeGenerator fakeInterpretationGenerator() {
            return new FakeGenerator();
        }

        @Bean
        @Primary
        MutableClock interpretationClock() {
            return new MutableClock();
        }
    }

    static final String VALID = "{\"interpretation\":\"관찰 자료가 적습니다. 바다와 자유로운 이동이 관찰됩니다.\"}";

    static class FakeGenerator implements InterpretationGenerator {
        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        volatile boolean available = true;
        volatile java.util.function.Function<Input, CompletableFuture<String>> action =
                i -> CompletableFuture.completedFuture(VALID);

        public boolean available() {
            return available;
        }

        public CompletableFuture<String> generate(Input input) {
            assertThat(
                            org.springframework.transaction.support
                                    .TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            calls.incrementAndGet();
            return action.apply(input);
        }
    }

    static class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-10-28T03:00:00Z");

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        public Instant instant() {
            return now;
        }
    }

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired InterpretationService service;
    @Autowired FakeGenerator generator;
    @Autowired InterpretationTransactions transactions;
    @Autowired InterpretationTargets targets;
    @Autowired RecentInterpretationRepository rows;
    @Autowired DreamService dreams;
    @Autowired DreamStructureService structures;
    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MutableClock clock;
    Long user;
    LocalDate today = LocalDate.of(2026, 10, 28);

    @BeforeEach
    void setup() {
        clock.now = Instant.parse("2026-10-28T03:00:00Z");
        generator.calls.set(0);
        generator.available = true;
        generator.action = i -> CompletableFuture.completedFuture(VALID);
        user = users.saveAndFlush(User.create(UUID.randomUUID() + "@test.invalid", "테스터")).getId();
    }

    DreamResponse analyzed(LocalDate date) {
        var created = dreams.create(user, new DreamCreateRequest(date, "바다 위를 날았다"));
        var completed =
                dreams.complete(
                        user,
                        created.dreamId(),
                        new DreamEmotionsRequest(created.revision(), List.of(DreamEmotion.HAPPY)));
        await(structures.analyze(user, completed.dreamId(), completed.revision()));
        return dreams.get(user, completed.dreamId());
    }

    InterpretationTargets.Target current() {
        return new TransactionTemplate(transactionManager).execute(s -> targets.current(user));
    }

    void storeCompleted() {
        var target = current();
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        s -> {
                            var row =
                                    RecentInterpretation.create(users.findById(user).orElseThrow());
                            row.start(
                                    target.fingerprint(),
                                    targets.encodeIds(target.dreamIds()),
                                    "{}",
                                    clock.instant());
                            row.finish(
                                    "바다와 자유로운 이동이 관찰됩니다.",
                                    targets.encodeSources(target.sources()),
                                    clock.instant());
                            rows.saveAndFlush(row);
                        });
    }

    @Test
    void readDoesNotGenerateAndKeepsRecentSelectionRules() {
        assertThat(transactions.get(user).status()).isEqualTo(InterpretationResponse.State.EMPTY);
        for (int i = 0; i < 12; i++) analyzed(today.minusDays(i));
        analyzed(today.minusDays(30));
        var result = transactions.get(user);
        assertThat(result.status()).isEqualTo(InterpretationResponse.State.NOT_GENERATED);
        assertThat(result.targetCount()).isEqualTo(10);
        assertThat(result.from()).isEqualTo(today.minusDays(29));
        assertThat(result.limitedEvidence()).isFalse();
        assertThat(rows.findByUserId(user)).isEmpty();
    }

    @Test
    void rangeBoundaryAndEvidenceWarningMatchExistingContext() {
        analyzed(today.minusDays(29));
        assertThat(transactions.get(user).targetCount()).isOne();
        assertThat(transactions.get(user).limitedEvidence()).isTrue();
        clock.now = clock.now.plus(Duration.ofDays(1));
        assertThat(transactions.get(user).status()).isEqualTo(InterpretationResponse.State.EMPTY);
    }

    @Test
    void storedResultIsPrivateAndChangedInputsNeedRefresh() {
        var dream = analyzed(today);
        storeCompleted();
        assertThat(transactions.get(user).interpretation()).contains("바다");
        assertThat(transactions.get(user).refreshNeeded()).isFalse();
        var update = new DreamUpdateRequest();
        update.setRevision(dream.revision());
        update.setOriginalText("별을 보았다");
        dreams.update(user, dream.dreamId(), update);
        assertThat(transactions.get(user).refreshNeeded()).isTrue();
        assertThat(transactions.get(user).interpretation()).contains("바다");
        var other =
                users.saveAndFlush(User.create(UUID.randomUUID() + "@test.invalid", "다른이")).getId();
        assertThat(transactions.get(other).interpretation()).isNull();
        assertThatThrownBy(() -> transactions.get(Long.MAX_VALUE))
                .isInstanceOf(BusinessException.class);
        var unregistered =
                users.saveAndFlush(User.register(UUID.randomUUID() + "@test.invalid")).getId();
        assertThatThrownBy(() -> transactions.get(unregistered))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void deletingSourcePurgesStoredInterpretationWithoutRecreation() {
        var dream = analyzed(today);
        storeCompleted();
        dreams.delete(user, dream.dreamId(), dream.revision());
        var row = rows.findByUserId(user).orElseThrow();
        assertThat(row.getBody()).isNull();
        assertThat(row.getResultSources()).isNull();
        assertThat(row.isEverCompleted()).isTrue();
        assertThat(transactions.get(user).status()).isEqualTo(InterpretationResponse.State.EMPTY);
        analyzed(today);
        assertThat(transactions.get(user).refreshNeeded()).isTrue();
        assertThat(transactions.get(user).interpretation()).isNull();
    }

    @Test
    void firstEntryGeneratesOnceAndGetNeverCallsAi() {
        assertThat(await(service.ensure(user)).status())
                .isEqualTo(InterpretationResponse.State.EMPTY);
        assertThat(generator.calls).hasValue(0);
        analyzed(today);
        var result = await(service.ensure(user));
        assertThat(result.status()).isEqualTo(InterpretationResponse.State.COMPLETED);
        assertThat(result.interpretation()).contains("바다");
        assertThat(result.generatedAt()).isEqualTo(clock.instant());
        assertThat(result.sources()).hasSize(1);
        await(service.ensure(user));
        transactions.get(user);
        assertThat(generator.calls).hasValue(1);
        generator.available = false;
        assertThat(await(service.ensure(user)).interpretation()).isEqualTo(result.interpretation());
    }

    @Test
    void changedTargetsDoNotRegenerateOnEntryAfterSuccessfulResult() {
        analyzed(today);
        await(service.ensure(user));
        analyzed(today.minusDays(1));
        assertThat(await(service.ensure(user)).refreshNeeded()).isTrue();
        assertThat(generator.calls).hasValue(1);
    }

    @Test
    void concurrentFirstEntriesShareOneAttempt() {
        analyzed(today);
        var pending = new CompletableFuture<String>();
        generator.action = i -> pending;
        var first = service.ensure(user);
        var duplicate = await(service.ensure(user));
        assertThat(duplicate.status()).isEqualTo(InterpretationResponse.State.PROCESSING);
        assertThat(generator.calls).hasValue(1);
        pending.complete(VALID);
        assertThat(await(first).status()).isEqualTo(InterpretationResponse.State.COMPLETED);
    }

    @Test
    void failedFirstGenerationCanBeRetriedAndProviderTextIsNotExposed() {
        analyzed(today);
        generator.action =
                i -> CompletableFuture.failedFuture(new IllegalStateException("private response"));
        assertThat(await(service.ensure(user)).failureCode()).isEqualTo("CALL_FAILED");
        generator.action = i -> CompletableFuture.completedFuture("{\"interpretation\":7}");
        assertThat(await(service.ensure(user)).failureCode()).isEqualTo("INVALID_OUTPUT");
        generator.action = i -> CompletableFuture.completedFuture(VALID);
        assertThat(await(service.ensure(user)).status())
                .isEqualTo(InterpretationResponse.State.COMPLETED);
        assertThat(generator.calls).hasValue(3);
    }

    @Test
    void editsDuringGenerationRejectOldResultsWithoutHoldingSourceLocks() {
        var dream = analyzed(today);
        var pending = new CompletableFuture<String>();
        generator.action = i -> pending;
        var first = service.ensure(user);
        var update = new DreamUpdateRequest();
        update.setRevision(dream.revision());
        update.setEmotions(List.of(DreamEmotion.SAD));
        dreams.update(user, dream.dreamId(), update);
        pending.complete(VALID);
        var failed = await(first);
        assertThat(failed.failureCode()).isEqualTo("SOURCE_CHANGED");
        assertThat(failed.interpretation()).isNull();
    }

    @Test
    void deletedSourcesRejectLateCompletionAndPurgeStoredReferences() {
        var dream = analyzed(today);
        var pending = new CompletableFuture<String>();
        generator.action = i -> pending;
        var first = service.ensure(user);
        dreams.delete(user, dream.dreamId(), dream.revision());
        pending.complete(VALID);
        assertThat(await(first).interpretation()).isNull();
        var row = rows.findByUserId(user).orElseThrow();
        assertThat(row.getAttemptDreamIds()).isNull();
        assertThat(row.getBody()).isNull();
        assertThat(row.getFailureCode()).isEqualTo("SOURCE_DELETED");
    }

    @Test
    void expiredAttemptCannotReplaceNewerCompletedResult() {
        analyzed(today);
        var pending = new CompletableFuture<String>();
        generator.action = i -> pending;
        var first = service.ensure(user);
        clock.now = clock.now.plusSeconds(121);
        generator.action = i -> CompletableFuture.completedFuture(VALID.replace("바다", "새 결과"));
        var newer = await(service.ensure(user));
        pending.complete(VALID);
        assertThat(await(first).interpretation()).isEqualTo(newer.interpretation());
        assertThat(generator.calls).hasValue(2);
    }

    @Test
    void missingCredentialsRejectNewCallsBeforeReservation() {
        analyzed(today);
        generator.available = false;
        assertThatThrownBy(() -> service.ensure(user))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(InterpretationErrorCode.UNAVAILABLE));
        assertThat(rows.findByUserId(user)).isEmpty();
    }

    @Test
    void refreshReplacesSuccessfulResultOnlyWhenCurrentTargetsDiffer() {
        analyzed(today);
        var prior = await(service.ensure(user));
        analyzed(today.minusDays(1));
        assertThat(await(service.ensure(user)).generatedAt()).isEqualTo(prior.generatedAt());
        var pending = new CompletableFuture<String>();
        generator.action = i -> pending;
        var updated = service.refresh(user);
        var inProgress = await(service.refresh(user));
        assertThat(inProgress.status()).isEqualTo(InterpretationResponse.State.PROCESSING);
        assertThat(inProgress.interpretation()).isEqualTo(prior.interpretation());
        assertThat(generator.calls).hasValue(2);
        pending.complete(VALID.replace("바다", "두 꿈"));
        var result = await(updated);
        assertThat(result.refreshNeeded()).isFalse();
        assertThat(result.sources()).hasSize(2);
        assertThat(result.interpretation()).contains("두 꿈");
        await(service.refresh(user));
        assertThat(generator.calls).hasValue(2);
    }

    @Test
    void failedRefreshPreservesValidPriorResultAndCanRetryManually() {
        analyzed(today);
        var prior = await(service.ensure(user));
        analyzed(today.minusDays(1));
        generator.action =
                i -> CompletableFuture.failedFuture(new IllegalStateException("private"));
        var failed = await(service.refresh(user));
        assertThat(failed.status()).isEqualTo(InterpretationResponse.State.FAILED);
        assertThat(failed.interpretation()).isEqualTo(prior.interpretation());
        assertThat(failed.generatedAt()).isEqualTo(prior.generatedAt());
        assertThat(failed.refreshNeeded()).isTrue();
        await(service.ensure(user));
        assertThat(generator.calls).hasValue(2);
        generator.action = i -> CompletableFuture.completedFuture(VALID);
        assertThat(await(service.refresh(user)).refreshNeeded()).isFalse();
        assertThat(generator.calls).hasValue(3);
    }

    @Test
    void deletedSuccessfulSourceRequiresExplicitRefreshAndNeverResurrects() {
        var dream = analyzed(today);
        await(service.ensure(user));
        dreams.delete(user, dream.dreamId(), dream.revision());
        analyzed(today);
        assertThat(await(service.ensure(user)).interpretation()).isNull();
        assertThat(generator.calls).hasValue(1);
        var renewed = await(service.refresh(user));
        assertThat(renewed.sources())
                .extracting(InterpretationTargets.Source::dreamId)
                .doesNotContain(dream.dreamId());
        assertThat(renewed.refreshNeeded()).isFalse();
        assertThat(generator.calls).hasValue(2);
    }

    @Test
    void changingAnalysisWithoutEditingDreamNeedsRefreshAndMidnightReevaluatesMembership() {
        var old = analyzed(today.minusDays(29));
        analyzed(today.minusDays(2));
        await(service.ensure(user));
        clock.now = clock.now.plus(Duration.ofDays(1));
        var changed = transactions.get(user);
        assertThat(changed.targetCount()).isOne();
        assertThat(changed.refreshNeeded()).isTrue();
        var refreshed = await(service.refresh(user));
        assertThat(refreshed.sources())
                .extracting(InterpretationTargets.Source::dreamId)
                .doesNotContain(old.dreamId());
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status ->
                                // 성공 분석 결과 교체를 재현한다. 꿈 입력 sourceRevision은 유지한다.
                                jdbc.update(
                                        "update dream_scenes set content=? where analysis_id=?",
                                        "바다에서 수영했다",
                                        refreshed.sources().getFirst().analysisId()));
        assertThat(transactions.get(user).refreshNeeded()).isTrue();
    }

    @Test
    void dateChangeWithoutMembershipChangeKeepsSameResultAndTitleOnlyEditIsNotNewInput() {
        var dream = analyzed(today);
        await(service.ensure(user));
        clock.now = clock.now.plus(Duration.ofDays(1));
        assertThat(transactions.get(user).refreshNeeded()).isFalse();
        var update = new DreamUpdateRequest();
        update.setRevision(dream.revision());
        update.setTitle("새 제목");
        dreams.update(user, dream.dreamId(), update);
        assertThat(transactions.get(user).refreshNeeded()).isFalse();
    }

    @Test
    void featureFlagBlocksCallsAndAllowsReadOnlyCacheReuse() {
        analyzed(today);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "enabled", false);
        try {
            assertThatThrownBy(() -> service.ensure(user))
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            ex ->
                                    assertThat(ex.getErrorCode())
                                            .isEqualTo(InterpretationErrorCode.UNAVAILABLE));
            assertThat(transactions.get(user).status())
                    .isEqualTo(InterpretationResponse.State.NOT_GENERATED);
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(service, "enabled", true);
        }
    }

    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    int port;

    @Autowired com.mongle.backend.domain.auth.service.TokenService tokens;

    @Test
    void httpContractEnforcesAuthenticationAndDocumentsAllThreeOperations() throws Exception {
        clock.now = Instant.now();
        analyzed(today);
        String token = tokens.login(user).response().accessToken();
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            assertThat(http(client, "GET", "", null).statusCode()).isEqualTo(401);
            var unfinished = users.saveAndFlush(User.register(UUID.randomUUID() + "@test.invalid"));
            assertThat(
                            http(
                                            client,
                                            "GET",
                                            "",
                                            tokens.login(unfinished.getId())
                                                    .response()
                                                    .accessToken())
                                    .statusCode())
                    .isEqualTo(403);
            var get = http(client, "GET", "", token);
            assertThat(get.statusCode()).isEqualTo(200);
            assertThat(get.headers().firstValue("Cache-Control")).hasValue("no-store");
            assertThat(json.readTree(get.body()).path("data").path("status").asString())
                    .isEqualTo("NOT_GENERATED");
            var created = http(client, "POST", "", token);
            assertThat(created.statusCode()).isEqualTo(200);
            assertThat(json.readTree(created.body()).path("data").path("status").asString())
                    .isEqualTo("COMPLETED");
            analyzed(today.minusDays(1));
            var pending = new CompletableFuture<String>();
            generator.action = input -> pending;
            var first = service.refresh(user);
            var processing = http(client, "POST", "/refresh", token);
            assertThat(processing.statusCode()).isEqualTo(202);
            assertThat(json.readTree(processing.body()).path("data").path("status").asString())
                    .isEqualTo("PROCESSING");
            pending.complete(VALID);
            await(first);
            assertThat(http(client, "POST", "/refresh", token).statusCode()).isEqualTo(200);
            var spec =
                    client.send(
                            java.net.http.HttpRequest.newBuilder(
                                            java.net.URI.create(
                                                    "http://localhost:" + port + "/v3/api-docs"))
                                    .GET()
                                    .build(),
                            java.net.http.HttpResponse.BodyHandlers.ofString());
            var paths = json.readTree(spec.body()).path("paths");
            assertThat(paths.path("/api/v1/analysis-records/interpretation").has("get")).isTrue();
            assertThat(paths.path("/api/v1/analysis-records/interpretation").has("post")).isTrue();
            assertThat(paths.path("/api/v1/analysis-records/interpretation/refresh").has("post"))
                    .isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from credit_accounts where user_id=?",
                                    Long.class,
                                    user))
                    .isZero();
        }
    }

    private java.net.http.HttpResponse<String> http(
            java.net.http.HttpClient client, String method, String suffix, String token)
            throws Exception {
        var request =
                java.net.http.HttpRequest.newBuilder(
                                java.net.URI.create(
                                        "http://localhost:"
                                                + port
                                                + "/api/v1/analysis-records/interpretation"
                                                + suffix))
                        .method(method, java.net.http.HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(10));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void simultaneousReservationsUseSingleUserRowLock() throws Exception {
        analyzed(today);
        var pending = new CompletableFuture<String>();
        generator.action = input -> pending;
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(6)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var reservations =
                    new ArrayList<
                            java.util.concurrent.Future<
                                    CompletableFuture<InterpretationResponse>>>();
            for (int i = 0; i < 6; i++)
                reservations.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return service.ensure(user);
                                }));
            start.countDown();
            var responses = new ArrayList<CompletableFuture<InterpretationResponse>>();
            for (var reservation : reservations)
                responses.add(reservation.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(generator.calls).hasValue(1);
            pending.complete(VALID);
            for (var response : responses)
                assertThat(await(response).status())
                        .isIn(
                                InterpretationResponse.State.COMPLETED,
                                InterpretationResponse.State.PROCESSING);
            assertThat(rows.findByUserId(user)).isPresent();
        }
    }

    @Test
    void failedDreamDeletionRollsBackInterpretationPurgeTogether() {
        var dream = analyzed(today);
        var completed = await(service.ensure(user));
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> {
                            dreams.delete(user, dream.dreamId(), dream.revision());
                            status.setRollbackOnly();
                        });
        assertThat(dreams.get(user, dream.dreamId()).dreamId()).isEqualTo(dream.dreamId());
        assertThat(transactions.get(user).interpretation()).isEqualTo(completed.interpretation());
        assertThat(transactions.get(user).refreshNeeded()).isFalse();
    }
}
