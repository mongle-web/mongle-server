package com.mongle.backend.domain.dream.story;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.auth.service.TokenService;
import com.mongle.backend.domain.dream.analysis.*;
import com.mongle.backend.domain.dream.dto.*;
import com.mongle.backend.domain.dream.entity.DreamEmotion;
import com.mongle.backend.domain.dream.exception.DreamErrorCode;
import com.mongle.backend.domain.dream.service.DreamService;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.BusinessException;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties =
                "spring.datasource.url=jdbc:h2:mem:mongle-story;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
@Import(DreamStoryIntegrationTest.Config.class)
class DreamStoryIntegrationTest {
    private static final String STRUCTURE =
            """
{"generatedTitle":"바다에서 숲으로","displayKeywords":["바다","숲길"],"elements":[],"scenes":[
  {"sequence":1,"content":"바다 위를 날았다","disconnectedFromPrevious":false,"elementKeys":[]},
  {"sequence":2,"content":"숲길을 걸었다","disconnectedFromPrevious":true,"elementKeys":[]}
]}
""";

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeGenerator fakeStoryGenerator() {
            return new FakeGenerator();
        }

        @Bean
        @Primary
        StructureGenerator fakeStructureGenerator() {
            return input -> STRUCTURE;
        }
    }

    static class FakeGenerator implements StoryGenerator {
        final AtomicInteger calls = new AtomicInteger();
        volatile Function<Input, String> action = input -> StoryValidatorTest.VALID;
        volatile boolean enabled = true;

        @Override
        public boolean available() {
            return enabled;
        }

        @Override
        public String generate(Input input) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(input.originalText()).isEqualTo("바다 위를 날다가 숲길을 걸었다");
            assertThat(input.scenes()).hasSize(2);
            calls.incrementAndGet();

            return action.apply(input);
        }
    }

    @Value("${local.server.port}")
    int port;

    @Autowired DreamStoryService service;
    @Autowired StoryTransactions transactions;
    @Autowired DreamService dreams;
    @Autowired DreamStructureService structure;
    @Autowired UserRepository users;
    @Autowired FakeGenerator generator;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenService tokens;
    Long userId;
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @BeforeEach
    void setUp() {
        userId = users.saveAndFlush(User.create(UUID.randomUUID() + "@test.com", "테스터")).getId();
        generator.calls.set(0);
        generator.enabled = true;
        generator.action = input -> StoryValidatorTest.VALID;
    }

    private DreamResponse completed(boolean analyzed) {
        var dream = dreams.create(userId, new DreamCreateRequest(today, "바다 위를 날다가 숲길을 걸었다"));
        dream =
                dreams.complete(
                        userId,
                        dream.dreamId(),
                        new DreamEmotionsRequest(dream.revision(), List.of(DreamEmotion.HAPPY)));

        if (analyzed) {
            assertThat(structure.analyze(userId, dream.dreamId(), dream.revision()).status())
                    .isEqualTo(GenerationStatus.COMPLETED);
        }

        return dreams.get(userId, dream.dreamId());
    }

    private StoryRequest request(DreamResponse dream) {
        return new StoryRequest(dream.revision(), false, null);
    }

    @Test
    void storesOrderedStoryAndReusesItWithoutChangingDreamRevision() {
        var dream = completed(true);
        var story = service.generate(userId, dream.dreamId(), request(dream));

        assertThat(story.status()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(story.sections()).hasSize(3);
        assertThat(story.sourceChanged()).isFalse();
        assertThat(story.hasPreviousResult()).isFalse();
        assertThat(story.resultRevision()).isEqualTo(dream.sourceRevision());
        assertThat(story.resultPromptVersion()).isEqualTo(StoryPrompt.VERSION);
        assertThat(transactions.latest(userId, dream.dreamId())).isEqualTo(story);
        assertThat(transactions.get(userId, story.storyId())).isEqualTo(story);
        assertThat(service.generate(userId, dream.dreamId(), request(dream)).storyId())
                .isEqualTo(story.storyId());
        assertThat(generator.calls).hasValue(1);
        assertThat(dreams.get(userId, dream.dreamId()).revision()).isEqualTo(dream.revision());
    }

    @Test
    void titleEditsBeforeDuringAndAfterStoryDoNotInvalidateItsSource() {
        var dream = completed(true);
        var before = new DreamUpdateRequest();
        before.setRevision(dream.revision());
        before.setTitle("내 제목");
        var titled = dreams.update(userId, dream.dreamId(), before);
        generator.action =
                input -> {
                    var during = new DreamUpdateRequest();
                    during.setRevision(titled.revision());
                    during.setTitle("");
                    dreams.update(userId, dream.dreamId(), during);
                    return StoryValidatorTest.VALID;
                };
        var story = service.generate(userId, dream.dreamId(), request(titled));
        assertThat(story.status()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(story.sourceChanged()).isFalse();
        var current = dreams.get(userId, dream.dreamId());
        assertThat(current.title()).isNull();
        assertThat(current.sourceRevision()).isEqualTo(dream.sourceRevision());
        assertError(
                () -> service.generate(userId, dream.dreamId(), request(titled)),
                DreamErrorCode.VERSION_CONFLICT);
        var after = new DreamUpdateRequest();
        after.setRevision(current.revision());
        after.setTitle("수정 제목");
        current = dreams.update(userId, dream.dreamId(), after);
        assertThat(service.generate(userId, dream.dreamId(), request(current)).storyId())
                .isEqualTo(story.storyId());
        assertThat(transactions.get(userId, story.storyId()).sourceChanged()).isFalse();
        assertThat(generator.calls).hasValue(1);
    }

    @Test
    void regenerationReplacesResultAndRejectsReplayedOldVersion() {
        var dream = completed(true);
        var first = service.generate(userId, dream.dreamId(), request(dream));
        var regeneration = new StoryRequest(dream.revision(), true, first.storyVersion());
        generator.action = input -> StoryValidatorTest.VALID.replace("바다 위를 날았다.", "바다 위로 날아올랐다.");

        var next = service.generate(userId, dream.dreamId(), regeneration);

        assertThat(next.storyId()).isEqualTo(first.storyId());
        assertThat(next.storyVersion()).isGreaterThan(first.storyVersion());
        assertThat(next.sections().getFirst().content()).isEqualTo("바다 위로 날아올랐다.");
        assertError(
                () -> service.generate(userId, dream.dreamId(), regeneration),
                StoryErrorCode.VERSION_CONFLICT);
        assertThat(generator.calls).hasValue(2);
    }

    @Test
    void failedRegenerationKeepsPreviousResultAndRetryReplacesIt() {
        var dream = completed(true);
        var first = service.generate(userId, dream.dreamId(), request(dream));
        generator.action = input -> "{}";

        var failed =
                service.generate(
                        userId,
                        dream.dreamId(),
                        new StoryRequest(dream.revision(), true, first.storyVersion()));

        assertThat(failed.status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(failed.failureCode()).isEqualTo("INVALID_OUTPUT");
        assertThat(failed.hasPreviousResult()).isTrue();
        assertThat(failed.sections()).isEqualTo(first.sections());
        generator.action = input -> StoryValidatorTest.VALID;

        var retried = service.generate(userId, dream.dreamId(), request(dream));

        assertThat(retried.status()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(retried.hasPreviousResult()).isFalse();
        assertThat(retried.storyId()).isEqualTo(first.storyId());
    }

    @Test
    void processingRegenerationExposesPreviousResultAndReusesTheAttempt() {
        var dream = completed(true);
        var first = service.generate(userId, dream.dreamId(), request(dream));
        var request = new StoryRequest(dream.revision(), true, first.storyVersion());
        var reservation = transactions.begin(userId, dream.dreamId(), request, true);

        assertThat(reservation.response().hasPreviousResult()).isTrue();
        assertThat(reservation.response().sections()).isEqualTo(first.sections());
        var duplicate = service.generate(userId, dream.dreamId(), request);
        assertThat(duplicate.status()).isEqualTo(GenerationStatus.PROCESSING);
        assertThat(duplicate.storyVersion()).isEqualTo(reservation.response().storyVersion());
        assertThat(generator.calls).hasValue(1);

        transactions.fail(reservation.input(), "CALL_FAILED");
    }

    @Test
    void failedFirstCallLeavesNoResultAndCanRetry() {
        var dream = completed(true);
        generator.action =
                input -> {
                    throw new IllegalStateException("비공개 출력");
                };

        var failed = service.generate(userId, dream.dreamId(), request(dream));

        assertThat(failed.status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(failed.failureCode()).isEqualTo("CALL_FAILED");
        assertThat(failed.sections()).isEmpty();
        generator.action = input -> StoryValidatorTest.VALID;
        assertThat(service.generate(userId, dream.dreamId(), request(dream)).status())
                .isEqualTo(GenerationStatus.COMPLETED);
    }

    @Test
    void unavailableGeneratorDoesNotReserveAnAttempt() {
        var dream = completed(true);
        generator.enabled = false;

        assertError(
                () -> service.generate(userId, dream.dreamId(), request(dream)),
                StoryErrorCode.UNAVAILABLE);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from dream_stories where user_id=?",
                                Long.class,
                                userId))
                .isZero();
        assertThat(generator.calls).hasValue(0);
    }

    @Test
    void requiresCompletedDreamCurrentRevisionAndFreshCompletedAnalysis() {
        var draft = dreams.create(userId, new DreamCreateRequest(today, "작성 중"));
        assertError(
                () -> service.generate(userId, draft.dreamId(), request(draft)),
                DreamErrorCode.INVALID_STATE);
        var dream =
                dreams.complete(
                        userId,
                        draft.dreamId(),
                        new DreamEmotionsRequest(draft.revision(), List.of(DreamEmotion.HAPPY)));
        assertError(
                () -> service.generate(userId, dream.dreamId(), request(dream)),
                StoryErrorCode.ANALYSIS_REQUIRED);
        structure.analyze(userId, dream.dreamId(), dream.revision());
        var edit = new DreamUpdateRequest();
        edit.setRevision(dreams.get(userId, dream.dreamId()).revision());
        edit.setOriginalText("수정한 원문");
        var changed = dreams.update(userId, dream.dreamId(), edit);

        assertError(
                () -> service.generate(userId, dream.dreamId(), request(dream)),
                DreamErrorCode.VERSION_CONFLICT);
        assertError(
                () -> service.generate(userId, changed.dreamId(), request(changed)),
                StoryErrorCode.ANALYSIS_STALE);
        assertThat(generator.calls).hasValue(0);
    }

    @Test
    void editDuringGenerationDiscardsOutputAndDoesNotAlterAnalysis() {
        var dream = completed(true);
        generator.action =
                input -> {
                    var edit = new DreamUpdateRequest();
                    edit.setRevision(dream.revision());
                    edit.setOriginalText("수정한 원문");
                    dreams.update(userId, dream.dreamId(), edit);

                    return StoryValidatorTest.VALID;
                };

        var failed = service.generate(userId, dream.dreamId(), request(dream));

        assertThat(failed.failureCode()).isEqualTo("SOURCE_CHANGED");
        assertThat(failed.sections()).isEmpty();
        assertThat(failed.sourceChanged()).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "select status from dream_analyses where id=?",
                                String.class,
                                failed.analysisId()))
                .isEqualTo("COMPLETED");
    }

    @Test
    void sourceEditsMarkPreservedStoryAsChanged() {
        var dream = completed(true);
        var story = service.generate(userId, dream.dreamId(), request(dream));
        var edit = new DreamUpdateRequest();
        edit.setRevision(dream.revision());
        edit.setOriginalText("다시 기록한 원문");
        dreams.update(userId, dream.dreamId(), edit);

        var kept = transactions.get(userId, story.storyId());

        assertThat(kept.sourceChanged()).isTrue();
        assertThat(kept.sections()).isEqualTo(story.sections());
    }

    @Test
    void deletionDuringGenerationFailsAttemptAndPreservesCompletedStory() {
        var dream = completed(true);
        var first = service.generate(userId, dream.dreamId(), request(dream));
        generator.action =
                input -> {
                    dreams.delete(userId, dream.dreamId(), dream.revision());

                    return StoryValidatorTest.VALID;
                };

        var failed =
                service.generate(
                        userId,
                        dream.dreamId(),
                        new StoryRequest(dream.revision(), true, first.storyVersion()));

        assertThat(failed.status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(failed.failureCode()).isEqualTo("SOURCE_DELETED");
        assertThat(failed.sourceDeleted()).isTrue();
        assertThat(failed.dreamId()).isNull();
        assertThat(failed.hasPreviousResult()).isTrue();
        assertThat(failed.sections()).isEqualTo(first.sections());
        assertThat(transactions.get(userId, failed.storyId()).sections())
                .isEqualTo(first.sections());
        assertError(() -> transactions.latest(userId, dream.dreamId()), DreamErrorCode.NOT_FOUND);

        var replacement = completed(true);
        generator.action = input -> StoryValidatorTest.VALID;
        assertThat(service.generate(userId, replacement.dreamId(), request(replacement)).storyId())
                .isNotEqualTo(first.storyId());
    }

    @Test
    void firstGenerationDeletionLeavesNoPartialResult() {
        var dream = completed(true);
        generator.action =
                input -> {
                    dreams.delete(userId, dream.dreamId(), dream.revision());
                    return StoryValidatorTest.VALID;
                };

        var failed = service.generate(userId, dream.dreamId(), request(dream));

        assertThat(failed.failureCode()).isEqualTo("SOURCE_DELETED");
        assertThat(failed.sections()).isEmpty();
    }

    @Test
    void concurrentDuplicateUsesOneAiCallAndReturnsProcessing() throws Exception {
        var dream = completed(true);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        generator.action =
                input -> {
                    entered.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException();
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException();
                    }

                    return StoryValidatorTest.VALID;
                };

        try (var executor = Executors.newSingleThreadExecutor()) {
            var future =
                    executor.submit(
                            () -> service.generate(userId, dream.dreamId(), request(dream)));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                var duplicate = service.generate(userId, dream.dreamId(), request(dream));
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
    void expiredOldSuccessAndFailureCannotOverwriteNewAttempt() {
        var dream = completed(true);
        var first = transactions.begin(userId, dream.dreamId(), request(dream), true);
        expire(first.input().storyId());
        var second = transactions.begin(userId, dream.dreamId(), request(dream), true);
        var result = new StoryValidator().parse(StoryValidatorTest.VALID, second.input().scenes());
        var completed = transactions.finish(second.input(), result);

        assertThat(second.input().attemptId()).isNotEqualTo(first.input().attemptId());
        assertThat(transactions.finish(first.input(), result)).isEqualTo(completed);
        assertThat(transactions.fail(first.input(), "CALL_FAILED")).isEqualTo(completed);
    }

    @Test
    void expiredAttemptCannotSaveEvenWithoutReplacement() {
        var dream = completed(true);
        var first = transactions.begin(userId, dream.dreamId(), request(dream), true);
        expire(first.input().storyId());

        var failed =
                transactions.finish(
                        first.input(),
                        new StoryValidator()
                                .parse(StoryValidatorTest.VALID, first.input().scenes()));

        assertThat(failed.failureCode()).isEqualTo("ATTEMPT_EXPIRED");
        assertThat(failed.sections()).isEmpty();
    }

    @Test
    void persistenceFailureRollsBackNewResultAndKeepsPreviousSuccess() {
        var dream = completed(true);
        var first = service.generate(userId, dream.dreamId(), request(dream));
        generator.action = input -> StoryValidatorTest.VALID.replace("바다 위를 날았다.", "새 결과");
        // 기존 행은 허용하고 이번 성공 갱신만 막는다.
        jdbc.execute(
                "alter table dream_stories add constraint ck_story_test_result check (id <> "
                        + first.storyId()
                        + " or result_json not like '%새 결과%')");

        try {
            assertError(
                    () ->
                            service.generate(
                                    userId,
                                    dream.dreamId(),
                                    new StoryRequest(dream.revision(), true, first.storyVersion())),
                    StoryErrorCode.CALL_FAILED);
            var failed = transactions.get(userId, first.storyId());
            assertThat(failed.failureCode()).isEqualTo("PERSISTENCE_FAILED");
            assertThat(failed.sections()).isEqualTo(first.sections());
            assertThat(failed.hasPreviousResult()).isTrue();
        } finally {
            jdbc.execute("alter table dream_stories drop constraint ck_story_test_result");
        }
    }

    @Test
    void databaseUniqueConstraintRejectsSecondStoryForSameAnalysis() {
        var dream = completed(true);
        var story = service.generate(userId, dream.dreamId(), request(dream));

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
insert into dream_stories (analysis_id,user_id,source_revision,prompt_version,status,attempt_id,version,created_at,updated_at)
values (?,?,?,'story-v1','PROCESSING','duplicate',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
""",
                                        story.analysisId(),
                                        userId,
                                        dream.sourceRevision()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void defaultsOptionalRegenerationFlagDuringJsonDeserialization() {
        var json = JsonMapper.builder().build();

        for (String body :
                List.of(
                        "{\"revision\":0}",
                        "{\"revision\":0,\"regenerate\":null}",
                        "{\"revision\":0,\"regenerate\":false}")) {
            var request = json.readValue(body, StoryRequest.class);

            assertThat(request.regenerate()).as(body).isFalse();
            assertThat(request.isRegenerationVersionProvided()).as(body).isTrue();
        }

        var regeneration =
                json.readValue(
                        "{\"revision\":0,\"regenerate\":true,\"storyVersion\":0}",
                        StoryRequest.class);

        assertThat(regeneration.regenerate()).isTrue();
        assertThat(regeneration.storyVersion()).isZero();
        assertThat(regeneration.isRegenerationVersionProvided()).isTrue();
    }

    @Test
    void apiEnforcesAuthenticationOwnershipValidationAndNoStore() throws Exception {
        var dream = completed(true);
        var story = service.generate(userId, dream.dreamId(), request(dream));
        var token = tokens.login(userId).response().accessToken();
        var otherUser =
                users.saveAndFlush(User.create(UUID.randomUUID() + "@test.com", "타인")).getId();
        var stranger = tokens.login(otherUser).response().accessToken();

        try (var client = HttpClient.newHttpClient()) {
            assertStatus(send(client, "/stories/" + story.storyId(), null, null), 401);
            assertStatus(send(client, "/stories/" + story.storyId(), stranger, null), 404);
            assertStatus(send(client, "/stories/" + Long.MAX_VALUE, token, null), 404);
            var found = send(client, "/stories/" + story.storyId(), token, null);
            assertStatus(found, 200);
            assertThat(found.headers().firstValue("Cache-Control")).contains("no-store");
            assertStatus(send(client, "/dreams/" + dream.dreamId() + "/story", token, null), 200);
            assertStatus(
                    send(
                            client,
                            "/dreams/" + dream.dreamId() + "/story",
                            stranger,
                            "{\"revision\":" + dream.revision() + "}"),
                    404);
            for (String invalid :
                    List.of(
                            "{}",
                            "{\"revision\":-1}",
                            "{\"revision\":0,\"regenerate\":true}",
                            "{\"revision\":0,\"storyVersion\":-1}")) {
                assertStatus(
                        send(client, "/dreams/" + dream.dreamId() + "/story", token, invalid), 400);
            }
            var valid =
                    send(
                            client,
                            "/dreams/" + dream.dreamId() + "/story",
                            token,
                            "{\"revision\":" + dream.revision() + "}");
            assertStatus(valid, 200);
            assertThat(valid.headers().firstValue("Cache-Control")).contains("no-store");
        }
    }

    @Test
    void apiReturns202ForProcessingAnd503WithoutGateway() throws Exception {
        var dream = completed(true);
        var token = tokens.login(userId).response().accessToken();
        String path = "/dreams/" + dream.dreamId() + "/story";
        String body = "{\"revision\":" + dream.revision() + "}";

        try (var client = HttpClient.newHttpClient()) {
            generator.enabled = false;
            assertStatus(send(client, path, token, body), 503);
            generator.enabled = true;
            var reserved = transactions.begin(userId, dream.dreamId(), request(dream), true);
            var processing = send(client, path, token, body);
            assertStatus(processing, 202);
            assertThat(processing.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(generator.calls).hasValue(0);
            transactions.fail(reserved.input(), "CALL_FAILED");
        }
    }

    private HttpResponse<String> send(HttpClient client, String path, String token, String body)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body == null) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        }

        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertStatus(HttpResponse<String> response, int expected) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
    }

    private void expire(Long storyId) {
        jdbc.update(
                "update dream_stories set lease_until=TIMESTAMP '2000-01-01 00:00:00' where id=?",
                storyId);
    }

    private void assertError(Runnable action, com.mongle.backend.global.error.ErrorCode error) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(error));
    }
}
