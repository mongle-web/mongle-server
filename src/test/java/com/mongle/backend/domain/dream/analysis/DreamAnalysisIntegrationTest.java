package com.mongle.backend.domain.dream.analysis;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.dream.dto.*;
import com.mongle.backend.domain.dream.entity.*;
import com.mongle.backend.domain.dream.exception.DreamErrorCode;
import com.mongle.backend.domain.dream.service.DreamService;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.BusinessException;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties =
                "spring.datasource.url=jdbc:h2:mem:mongle-analysis;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
@Import(DreamAnalysisIntegrationTest.Config.class)
class DreamAnalysisIntegrationTest {
    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeGenerator fakeGenerator() {
            return new FakeGenerator();
        }
    }

    static class FakeGenerator implements StructureGenerator {
        AtomicInteger calls = new AtomicInteger();
        volatile Function<Input, String> action = i -> StructureValidatorTest.VALID;

        public String generate(Input i) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calls.incrementAndGet();
            return action.apply(i);
        }
    }

    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    int port;

    @Autowired com.mongle.backend.domain.auth.service.TokenService tokens;
    @Autowired DreamStructureService service;
    @Autowired AnalysisTransactions transactions;
    @Autowired DreamService dreams;
    @Autowired UserRepository users;
    @Autowired FakeGenerator generator;
    @Autowired JdbcTemplate jdbc;
    Long user;
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @BeforeEach
    void setUp() {
        user = users.saveAndFlush(User.create(UUID.randomUUID() + "@test.com", "테스터")).getId();
        generator.calls.set(0);
        generator.action = i -> StructureValidatorTest.VALID;
    }

    private DreamResponse completed(LocalDate date) {
        var d = dreams.create(user, new DreamCreateRequest(date, "바다 위를 날았다"));
        return dreams.complete(
                user,
                d.dreamId(),
                new DreamEmotionsRequest(d.revision(), List.of(DreamEmotion.HAPPY)));
    }

    @Test
    void analysisStatusChangesKeepSourceRevisionAndStillUpdateAuditTimestamp() {
        var dream = completed(today);
        jdbc.update(
                "update dreams set updated_at=TIMESTAMP '2000-01-01 00:00:00' where id=?",
                dream.dreamId());

        var reservation = transactions.begin(user, dream.dreamId(), dream.revision(), true);
        var processing = dreams.get(user, dream.dreamId());

        assertThat(processing.analysisStatus()).isEqualTo(GenerationStatus.PROCESSING);
        assertThat(processing.revision()).isEqualTo(dream.revision());
        assertThat(reservation.response().sourceChanged()).isFalse();
        assertAuditTimestampWasUpdated(dream.dreamId());

        long analysisVersion =
                jdbc.queryForObject(
                        "select version from dream_analyses where id=?",
                        Long.class,
                        reservation.response().analysisId());

        jdbc.update(
                "update dreams set updated_at=TIMESTAMP '2000-01-01 00:00:00' where id=?",
                dream.dreamId());

        var output = new StructureValidator().parse(StructureValidatorTest.VALID);
        var analysis = transactions.finish(reservation.input(), output);
        var completed = dreams.get(user, dream.dreamId());

        assertThat(completed.analysisStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(completed.revision()).isEqualTo(dream.revision());
        assertThat(analysis.sourceRevision()).isEqualTo(dream.sourceRevision());
        assertThat(analysis.sourceChanged()).isFalse();
        assertAuditTimestampWasUpdated(dream.dreamId());
        assertThat(
                        jdbc.queryForObject(
                                "select version from dream_analyses where id=?",
                                Long.class,
                                analysis.analysisId()))
                .isGreaterThan(analysisVersion);
    }

    private void assertAuditTimestampWasUpdated(Long dreamId) {
        var updatedAt =
                jdbc.queryForObject(
                        "select updated_at from dreams where id=?",
                        java.sql.Timestamp.class,
                        dreamId);

        assertThat(updatedAt.toLocalDateTime()).isAfter(LocalDateTime.of(2000, 1, 1, 0, 0));
    }

    @Test
    void failedAnalysisCanRetryWithOriginalRevision() {
        var dream = completed(today);
        generator.action =
                input -> {
                    throw new IllegalStateException("테스트 생성 실패");
                };

        var failed = service.analyze(user, dream.dreamId(), dream.revision());

        assertThat(failed.status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(failed.failureCode()).isEqualTo("CALL_FAILED");
        assertThat(failed.sourceChanged()).isFalse();
        assertThat(dreams.get(user, dream.dreamId()).revision()).isEqualTo(dream.revision());

        generator.action = input -> StructureValidatorTest.VALID;
        var retried = service.analyze(user, dream.dreamId(), dream.revision());

        assertThat(retried.analysisId()).isEqualTo(failed.analysisId());
        assertThat(retried.status()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(retried.sourceChanged()).isFalse();
        assertThat(dreams.get(user, dream.dreamId()).revision()).isEqualTo(dream.revision());
    }

    @Test
    void sourceFieldChangesRemainVersionedAfterAnalysis() {
        var dream = completed(today);
        var analysis = service.analyze(user, dream.dreamId(), dream.revision());
        long revision = dream.revision();

        List<Consumer<DreamUpdateRequest>> changes =
                List.of(
                        request -> request.setOriginalText("숲을 걷는 꿈"),
                        request -> request.setOriginalText("다른 숲을 걷는 꿈"));

        for (var change : changes) {
            var request = new DreamUpdateRequest();
            request.setRevision(revision);
            change.accept(request);

            var updated = dreams.update(user, dream.dreamId(), request);

            assertThat(updated.revision()).isGreaterThan(revision);
            assertThat(transactions.get(user, analysis.analysisId()).sourceChanged()).isTrue();
            assertThatThrownBy(() -> dreams.update(user, dream.dreamId(), request))
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            exception ->
                                    assertThat(exception.getErrorCode())
                                            .isEqualTo(DreamErrorCode.VERSION_CONFLICT));

            revision = updated.revision();
        }
    }

    @Test
    void savesAllResultsOnceAndDoesNotRegenerateAfterEdits() {
        var d = completed(today);
        var result = service.analyze(user, d.dreamId(), d.revision());
        assertThat(result.status()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(result.sourceChanged()).isFalse();
        assertThat(result.scenes()).hasSize(1);
        assertThat(result.elements()).hasSize(1);
        assertThat(service.analyze(user, d.dreamId(), d.revision()).analysisId())
                .isEqualTo(result.analysisId());
        var current = dreams.get(user, d.dreamId());
        var edit = new DreamUpdateRequest();
        edit.setRevision(current.revision());
        edit.setOriginalText("숲을 걷는 꿈");
        dreams.update(user, d.dreamId(), edit);
        assertThat(service.analyze(user, d.dreamId(), d.revision()).sourceChanged()).isTrue();
        assertThat(generator.calls).hasValue(1);
    }

    @Test
    void unavailableGatewayDoesNotCreateAnAttempt() {
        var d = completed(today);
        assertThatThrownBy(() -> transactions.begin(user, d.dreamId(), d.revision(), false))
                .isInstanceOf(BusinessException.class);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from dream_analyses where user_id=?",
                                Long.class,
                                user))
                .isZero();
    }

    @Test
    void rejectsIncompleteRecordsOwnershipAndMissingAnalysis() {
        var d = dreams.create(user, new DreamCreateRequest(today, "꿈"));
        assertThatThrownBy(() -> service.analyze(user, d.dreamId(), d.revision()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                service.analyze(
                                        users.saveAndFlush(
                                                        User.create(
                                                                UUID.randomUUID() + "@test.com",
                                                                "다른사람"))
                                                .getId(),
                                        d.dreamId(),
                                        d.revision()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> transactions.get(user, Long.MAX_VALUE))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void invalidJsonLeavesNoPartialResultsAndAllowsRetry() {
        var d = completed(today);
        generator.action = i -> "{}";
        var failed = service.analyze(user, d.dreamId(), d.revision());
        assertThat(failed.status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(failed.failureCode()).isEqualTo("INVALID_OUTPUT");
        assertThat(failed.scenes()).isEmpty();
        generator.action = i -> StructureValidatorTest.VALID;
        var fresh = dreams.get(user, d.dreamId());
        var retried = service.analyze(user, d.dreamId(), fresh.revision());
        assertThat(retried.analysisId()).isEqualTo(failed.analysisId());
        assertThat(retried.status()).isEqualTo(GenerationStatus.COMPLETED);
    }

    @Test
    void deletionPreservesGroupingAndAllowsAnotherDreamOnTheSameDate() {
        var d = completed(today);
        var a = service.analyze(user, d.dreamId(), d.revision());
        dreams.delete(user, d.dreamId(), d.revision());
        var kept = transactions.get(user, a.analysisId());
        assertThat(kept.sourceDeleted()).isTrue();
        assertThat(kept.dreamedAt()).isEqualTo(today);
        assertThat(kept.scenes()).hasSize(1);
        assertThat(kept.elements()).hasSize(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from dream_emotions where dream_id=?",
                                Long.class,
                                d.dreamId()))
                .isZero();
        var replacement = completed(today);
        var next = service.analyze(user, replacement.dreamId(), replacement.revision());
        assertThat(next.analysisId()).isNotEqualTo(a.analysisId());
    }

    @Test
    void modificationDuringGenerationDiscardsOutput() {
        var d = completed(today);
        generator.action =
                i -> {
                    var req = new DreamUpdateRequest();
                    req.setRevision(d.revision());
                    req.setOriginalText("원문 수정");
                    var updated = dreams.update(user, d.dreamId(), req);

                    assertThat(updated.revision()).isGreaterThan(d.revision());

                    return StructureValidatorTest.VALID;
                };
        var a = service.analyze(user, d.dreamId(), d.revision());
        assertThat(a.failureCode()).isEqualTo("SOURCE_CHANGED");
        assertThat(a.scenes()).isEmpty();
    }

    @Test
    void titleEditDuringAnalysisAndClearAfterCompletionKeepResultCurrent() {
        var dream = completed(today);
        generator.action =
                input -> {
                    var edit = new DreamUpdateRequest();
                    edit.setRevision(dream.revision());
                    edit.setTitle("내 제목");
                    var updated = dreams.update(user, dream.dreamId(), edit);
                    assertThat(updated.sourceRevision()).isEqualTo(dream.sourceRevision());
                    assertThat(updated.revision()).isGreaterThan(dream.revision());
                    return StructureValidatorTest.VALID;
                };
        var analysis = service.analyze(user, dream.dreamId(), dream.revision());
        assertThat(analysis.status()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(analysis.sourceChanged()).isFalse();
        var edit = new DreamUpdateRequest();
        edit.setRevision(dreams.get(user, dream.dreamId()).revision());
        edit.setTitle("");
        dreams.update(user, dream.dreamId(), edit);
        assertThat(transactions.get(user, analysis.analysisId()).sourceChanged()).isFalse();
        assertThat(generator.calls).hasValue(1);
    }

    @Test
    void editingThenRestoringSourceDuringAnalysisStillDiscardsAttempt() {
        var dream = completed(today);
        generator.action =
                input -> {
                    var first = new DreamUpdateRequest();
                    first.setRevision(dream.revision());
                    first.setOriginalText("임시 원문");
                    var changed = dreams.update(user, dream.dreamId(), first);
                    var restore = new DreamUpdateRequest();
                    restore.setRevision(changed.revision());
                    restore.setOriginalText(dream.originalText());
                    var restored = dreams.update(user, dream.dreamId(), restore);
                    assertThat(restored.sourceRevision()).isEqualTo(dream.sourceRevision() + 2);
                    return StructureValidatorTest.VALID;
                };
        var analysis = service.analyze(user, dream.dreamId(), dream.revision());
        assertThat(analysis.failureCode()).isEqualTo("SOURCE_CHANGED");
        assertThat(analysis.scenes()).isEmpty();
    }

    @Test
    void deletionDuringGenerationDiscardsOutput() {
        var d = completed(today);
        generator.action =
                i -> {
                    dreams.delete(user, d.dreamId(), d.revision());
                    return StructureValidatorTest.VALID;
                };
        var a = service.analyze(user, d.dreamId(), d.revision());
        assertThat(a.failureCode()).isEqualTo("SOURCE_DELETED");
        assertThat(a.scenes()).isEmpty();
        assertThat(a.sourceDeleted()).isTrue();
    }

    @Test
    void concurrentDuplicateDoesNotCallAiAgain() throws Exception {
        var d = completed(today);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        generator.action =
                i -> {
                    entered.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException();
                    }
                    return StructureValidatorTest.VALID;
                };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> service.analyze(user, d.dreamId(), d.revision()));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                var duplicate = service.analyze(user, d.dreamId(), d.revision());
                assertThat(duplicate.status()).isEqualTo(GenerationStatus.PROCESSING);
                assertThat(generator.calls).hasValue(1);
            } finally {
                release.countDown();
            }
            assertThat(future.get(10, TimeUnit.SECONDS).status())
                    .isEqualTo(GenerationStatus.COMPLETED);
        }
    }

    @Test
    void expiredAttemptCannotOverwriteNewAttempt() {
        var d = completed(today);
        var first = transactions.begin(user, d.dreamId(), d.revision(), true);
        // UTC로 매핑된 컬럼에 JVM 기본 시간대의 Timestamp를 바인딩하지 않는다.
        jdbc.update(
                "update dream_analyses set lease_until=TIMESTAMP '2000-01-01 00:00:00' where id=?",
                first.input().analysisId());
        var current = dreams.get(user, d.dreamId());
        var second = transactions.begin(user, d.dreamId(), current.revision(), true);
        assertThat(second.input()).isNotNull();
        assertThat(second.input().attemptId()).isNotEqualTo(first.input().attemptId());
        var output = new StructureValidator().parse(StructureValidatorTest.VALID);
        var saved = transactions.finish(second.input(), output);
        assertThat(transactions.finish(first.input(), output).analysisId())
                .isEqualTo(saved.analysisId());
        assertThat(transactions.get(user, saved.analysisId()).scenes()).hasSize(1);
    }

    @Test
    void failedLinkPersistenceRollsBackScenesAndElements() {
        var d = completed(today);
        // 앞선 테스트에서 저장한 연결은 허용하고 이번에 생성할 장면의 연결만 거절한다.
        long existingSceneId =
                jdbc.queryForObject("select coalesce(max(id),0) from dream_scenes", Long.class);
        jdbc.execute(
                "alter table dream_scene_entities add constraint ck_analysis_test_links check"
                        + " (dream_scene_id <= "
                        + existingSceneId
                        + ")");
        try {
            assertThatThrownBy(() -> service.analyze(user, d.dreamId(), d.revision()))
                    .isInstanceOf(BusinessException.class);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from dream_scenes where user_id=?",
                                    Long.class,
                                    user))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from dream_entities where user_id=?",
                                    Long.class,
                                    user))
                    .isZero();
            var failed =
                    jdbc.queryForMap(
                            "select status,failure_code from dream_analyses where user_id=?", user);
            assertThat(failed.get("status")).isEqualTo("FAILED");
            assertThat(failed.get("failure_code")).isEqualTo("PERSISTENCE_FAILED");
        } finally {
            jdbc.execute("alter table dream_scene_entities drop constraint ck_analysis_test_links");
        }
    }

    @Test
    void apiEnforcesAuthenticationOwnershipAndNoStore() throws Exception {
        var d = completed(today);
        var result = service.analyze(user, d.dreamId(), d.revision());
        var token = tokens.login(user).response().accessToken();
        var stranger =
                tokens.login(
                                users.saveAndFlush(
                                                User.create(UUID.randomUUID() + "@test.com", "타인"))
                                        .getId())
                        .response()
                        .accessToken();
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            var uri =
                    java.net.URI.create(
                            "http://localhost:" + port + "/api/v1/analyses/" + result.analysisId());
            assertThat(
                            client.send(
                                            java.net.http.HttpRequest.newBuilder(uri).GET().build(),
                                            java.net.http.HttpResponse.BodyHandlers.ofString())
                                    .statusCode())
                    .isEqualTo(401);
            assertThat(
                            client.send(
                                            java.net.http.HttpRequest.newBuilder(uri)
                                                    .header("Authorization", "Bearer " + stranger)
                                                    .GET()
                                                    .build(),
                                            java.net.http.HttpResponse.BodyHandlers.ofString())
                                    .statusCode())
                    .isEqualTo(404);
            var response =
                    client.send(
                            java.net.http.HttpRequest.newBuilder(uri)
                                    .header("Authorization", "Bearer " + token)
                                    .GET()
                                    .build(),
                            java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        }
    }
}
