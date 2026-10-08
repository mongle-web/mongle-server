package com.mongle.backend.domain.dream.image;

import static com.mongle.backend.domain.dream.gateway.DreamAiTestAwait.await;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.auth.service.TokenService;
import com.mongle.backend.domain.dream.analysis.*;
import com.mongle.backend.domain.dream.dto.*;
import com.mongle.backend.domain.dream.entity.DreamEmotion;
import com.mongle.backend.domain.dream.service.DreamService;
import com.mongle.backend.domain.dream.story.*;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.BusinessException;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:mongle-image;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "mongle.image.styles[0]=test-style",
            "mongle.image.styles[1]=other-style",
            "mongle.image.styles[2]=third-style",
            "mongle.image.moods[0]=test-mood",
            "mongle.image.moods[1]=other-mood"
        })
@ActiveProfiles("test")
@Import(DreamImageIntegrationTest.Config.class)
class DreamImageIntegrationTest {
    static final String STRUCTURE =
            """
{"generatedTitle":"바다","displayKeywords":["바다"],"elements":[],"scenes":[
  {"sequence":1,"content":"바다를 보았다","disconnectedFromPrevious":false,"elementKeys":[]}]}
""";
    static final String STORY =
            """
            {"sections":[{"sequence":1,"kind":"SCENE","sceneSequence":1,"content":"바다를 보았다."}]}
            """;

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        StructureGenerator structure() {
            return input -> CompletableFuture.completedFuture(STRUCTURE);
        }

        @Bean
        @Primary
        FakeStory story() {
            return new FakeStory();
        }

        @Bean
        @Primary
        FakeGenerator generator() {
            return new FakeGenerator();
        }

        @Bean
        @Primary
        FakeStore store() {
            return new FakeStore();
        }
    }

    static class FakeStory implements StoryGenerator {
        volatile String output = STORY;

        @Override
        public CompletableFuture<String> generate(Input input) {
            return CompletableFuture.completedFuture(output);
        }
    }

    static class FakeGenerator implements ImageGenerator {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean enabled = true;
        volatile CompletableFuture<byte[]> pending;
        volatile Function<Input, byte[]> action = input -> ImagePayloadTest.png();

        @Override
        public boolean available() {
            return enabled;
        }

        @Override
        public CompletableFuture<byte[]> generate(Input input) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(input.sections()).hasSize(1);
            calls.incrementAndGet();
            return pending == null ? CompletableFuture.completedFuture(action.apply(input)) : pending;
        }
    }

    static class FakeStore implements ImageAssetStore {
        final Map<String, byte[]> stored = new ConcurrentHashMap<>();
        final Set<String> deleted = ConcurrentHashMap.newKeySet();
        volatile boolean enabled = true, failPut = false;

        @Override
        public boolean available() {
            return enabled;
        }

        @Override
        public void put(String key, ImagePayload payload) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            stored.put(key, payload.bytes());
            if (failPut) throw new IllegalStateException("partial upload");
        }

        @Override
        public void delete(String key) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            stored.remove(key);
            deleted.add(key);
        }

        @Override
        public DownloadUrl temporaryUrl(String key, Duration lifetime) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(stored).containsKey(key);
            return new DownloadUrl("https://images.test/" + key, Instant.now().plusSeconds(240));
        }
    }

    @Autowired DreamImageService service;
    @Autowired ImageTransactions transactions;
    @Autowired DreamService dreams;
    @Autowired DreamStructureService analyses;
    @Autowired DreamStoryService stories;
    @Autowired StoryTransactions storyTransactions;
    @Autowired UserRepository users;
    @Autowired FakeGenerator generator;
    @Autowired FakeStore storage;
    @Autowired FakeStory storyteller;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenService tokens;

    @Value("${local.server.port}")
    int port;

    Long userId;

    @BeforeEach
    void setup() {
        userId = users.saveAndFlush(User.create(UUID.randomUUID() + "@test.com", "테스터")).getId();
        generator.calls.set(0);
        generator.enabled = true;
        generator.pending = null;
        generator.action = input -> ImagePayloadTest.png();
        storage.stored.clear();
        storage.deleted.clear();
        storage.enabled = true;
        storage.failPut = false;
        storyteller.output = STORY;
    }

    DreamResponse ready() {
        return ready(LocalDate.now(ZoneId.of("Asia/Seoul")));
    }

    DreamResponse ready(LocalDate date) {
        var dream =
                dreams.create(
                        userId,
                        new DreamCreateRequest(date, "바다를 보았다"));
        dream =
                dreams.complete(
                        userId,
                        dream.dreamId(),
                        new DreamEmotionsRequest(dream.revision(), List.of(DreamEmotion.HAPPY)));
        await(analyses.analyze(userId, dream.dreamId(), dream.revision()));
        dream = dreams.get(userId, dream.dreamId());
        await(
                stories.generate(
                        userId, dream.dreamId(), new StoryRequest(dream.revision(), false, null)));
        return dream;
    }

    ImageRequest request(DreamResponse dream) {
        return new ImageRequest(dream.revision(), "test-style", "test-mood", false, null);
    }

    ImageResponse generate(DreamResponse dream) {
        return await(service.generate(userId, dream.dreamId(), request(dream)));
    }

    @Test
    void storesReusesAndFetchesPrivateAssetWithoutChangingDream() {
        var dream = ready();
        var result = generate(dream);
        assertThat(result.status()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(result.width()).isEqualTo(2);
        assertThat(result.height()).isEqualTo(3);
        assertThat(result.resultStyle()).isEqualTo("test-style");
        assertThat(transactions.latest(userId, dream.dreamId())).isEqualTo(result);
        assertThat(generate(dream)).isEqualTo(result);
        assertThat(generator.calls).hasValue(1);
        assertThat(dreams.get(userId, dream.dreamId()).revision()).isEqualTo(dream.revision());
        assertThat(service.downloadUrl(userId, result.imageId()).url())
                .startsWith("https://images.test/");
    }

    @Test
    void retainsSuccessWhenRegenerationFailsAndRejectsStaleVersions() {
        var dream = ready();
        var first = generate(dream);
        var oldKey = transactions.assetKey(userId, first.imageId());
        generator.action =
                input -> {
                    throw new IllegalStateException("provider unavailable");
                };
        var regenerate =
                new ImageRequest(dream.revision(), "other-style", null, true, first.imageVersion());
        var failed = await(service.generate(userId, dream.dreamId(), regenerate));
        assertThat(failed.status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(failed.hasPreviousResult()).isTrue();
        assertThat(failed.style()).isEqualTo("other-style");
        assertThat(failed.resultStyle()).isEqualTo("test-style");
        assertThat(transactions.assetKey(userId, first.imageId())).isEqualTo(oldKey);
        assertThatThrownBy(() -> await(service.generate(userId, dream.dreamId(), regenerate)))
                .isInstanceOf(BusinessException.class);
        generator.action = input -> ImagePayloadTest.png();
        assertThat(
                        await(service.generate(
                                        userId,
                                        dream.dreamId(),
                                        new ImageRequest(
                                                dream.revision(),
                                                "other-style",
                                                null,
                                                true,
                                                failed.imageVersion())))
                                .status())
                .isEqualTo(GenerationStatus.COMPLETED);
    }

    @ParameterizedTest
    @EnumSource(
            value = GenerationStatus.class,
            names = {"FAILED", "PROCESSING"})
    void preservedSuccessRejectsChangedPlainRetries(GenerationStatus status) {
        var dream = ready();
        var first = generate(dream);
        String originalKey = transactions.assetKey(userId, first.imageId());
        var prior = interruptedRegeneration(dream, first, status);
        int calls = generator.calls.get();
        generator.action = input -> ImagePayloadTest.png();

        for (ImageRequest changed :
                List.of(
                        new ImageRequest(dream.revision(), "third-style", null, false, null),
                        new ImageRequest(
                                dream.revision(), "other-style", "other-mood", false, null),
                        request(dream))) {
            assertThatThrownBy(() -> await(service.generate(userId, dream.dreamId(), changed)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ImageErrorCode.REGENERATION_REQUIRED);
        }
        assertThat(transactions.get(userId, first.imageId())).isEqualTo(prior);
        assertThat(transactions.assetKey(userId, first.imageId())).isEqualTo(originalKey);
        assertThat(generator.calls).hasValue(calls);
        assertThat(storage.stored).containsOnlyKeys(originalKey);
        assertThat(storage.deleted).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = GenerationStatus.class,
            names = {"FAILED", "PROCESSING"})
    void preservedSuccessAllowsOnlySameAttemptPlainRetry(GenerationStatus status) {
        var dream = ready();
        var first = generate(dream);
        String originalKey = transactions.assetKey(userId, first.imageId());
        var prior = interruptedRegeneration(dream, first, status);
        int calls = generator.calls.get();
        generator.action = input -> ImagePayloadTest.png();
        var retry = new ImageRequest(dream.revision(), "other-style", null, false, null);

        var completed = await(service.generate(userId, dream.dreamId(), retry));
        assertThat(completed.status()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(completed.resultStyle()).isEqualTo("other-style");
        assertThat(completed.resultMood()).isNull();
        assertThat(completed.imageVersion()).isGreaterThan(prior.imageVersion());
        assertThat(generator.calls).hasValue(calls + 1);
        assertThat(storage.stored).containsKey(originalKey).hasSize(2);
        assertThat(await(service.generate(userId, dream.dreamId(), retry))).isEqualTo(completed);
        assertThat(generator.calls).hasValue(calls + 1);
    }

    @ParameterizedTest
    @EnumSource(
            value = GenerationStatus.class,
            names = {"FAILED", "PROCESSING"})
    void preservedSuccessRequiresCurrentVersionForDifferentRegeneration(GenerationStatus status) {
        var dream = ready();
        var first = generate(dream);
        var prior = interruptedRegeneration(dream, first, status);
        int calls = generator.calls.get();
        generator.action = input -> ImagePayloadTest.png();

        for (ImageRequest stale :
                List.of(
                        new ImageRequest(dream.revision(), "third-style", null, true, null),
                        new ImageRequest(
                                dream.revision(),
                                "third-style",
                                null,
                                true,
                                first.imageVersion()))) {
            assertThatThrownBy(() -> await(service.generate(userId, dream.dreamId(), stale)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ImageErrorCode.VERSION_CONFLICT);
        }
        assertThat(transactions.get(userId, first.imageId())).isEqualTo(prior);
        assertThat(generator.calls).hasValue(calls);
        var completed =
                await(service.generate(
                        userId,
                        dream.dreamId(),
                        new ImageRequest(
                                dream.revision(), "third-style", null, true, prior.imageVersion())));
        assertThat(completed.status()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(completed.resultStyle()).isEqualTo("third-style");
        assertThat(generator.calls).hasValue(calls + 1);
    }

    @ParameterizedTest
    @EnumSource(
            value = GenerationStatus.class,
            names = {"FAILED", "PROCESSING"})
    void preservedSuccessRejectsPlainRetryAfterStoryChanges(GenerationStatus status) {
        var dream = ready();
        var first = generate(dream);
        var prior = interruptedRegeneration(dream, first, status);
        int calls = generator.calls.get();
        generator.action = input -> ImagePayloadTest.png();
        var story = storyTransactions.latest(userId, dream.dreamId());
        storyteller.output = STORY.replace("바다를 보았다.", "바닷가를 바라보았다.");
        await(
                stories.generate(
                        userId,
                        dream.dreamId(),
                        new StoryRequest(dream.revision(), true, story.storyVersion())));

        assertThatThrownBy(
                        () ->
                                await(service.generate(
                                        userId,
                                        dream.dreamId(),
                                        new ImageRequest(
                                                dream.revision(),
                                                "other-style",
                                                null,
                                                false,
                                                null))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ImageErrorCode.REGENERATION_REQUIRED);
        var unchanged = transactions.get(userId, first.imageId());
        assertThat(unchanged.imageVersion()).isEqualTo(prior.imageVersion());
        assertThat(unchanged.status()).isEqualTo(status);
        assertThat(unchanged.storyChanged()).isTrue();
        assertThat(generator.calls).hasValue(calls);
        assertThat(storage.stored).hasSize(1);
    }

    private ImageResponse interruptedRegeneration(
            DreamResponse dream, ImageResponse first, GenerationStatus status) {
        var regenerate =
                new ImageRequest(dream.revision(), "other-style", null, true, first.imageVersion());
        if (status == GenerationStatus.FAILED) {
            generator.action =
                    input -> {
                        throw new IllegalStateException("provider unavailable");
                    };
            return await(service.generate(userId, dream.dreamId(), regenerate));
        }
        var pending = transactions.begin(userId, dream.dreamId(), regenerate, true).response();
        jdbc.update(
                "update dream_images set lease_until=? where id=?",
                java.sql.Timestamp.from(Instant.EPOCH),
                pending.imageId());
        return transactions.get(userId, pending.imageId());
    }

    @Test
    void optionsChangesNeedExplicitRegeneration() {
        var dream = ready();
        generate(dream);
        assertThatThrownBy(
                        () ->
                                await(service.generate(
                                        userId,
                                        dream.dreamId(),
                                        new ImageRequest(
                                                dream.revision(),
                                                "other-style",
                                                null,
                                                false,
                                                null))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ImageErrorCode.REGENERATION_REQUIRED);
        assertThat(generator.calls).hasValue(1);
    }

    @Test
    void invalidOutputAndPartialUploadAreCleanedAndRetryable() {
        var dream = ready();
        generator.action = input -> "not an image".getBytes();
        assertThat(generate(dream).failureCode()).isEqualTo("INVALID_OUTPUT");
        assertThat(storage.stored).isEmpty();
        generator.action = input -> ImagePayloadTest.png();
        storage.failPut = true;
        assertThat(generate(dream).failureCode()).isEqualTo("STORAGE_FAILED");
        assertThat(storage.stored).isEmpty();
        assertThat(storage.deleted).hasSize(1);
        storage.failPut = false;
        assertThat(generate(dream).status()).isEqualTo(GenerationStatus.COMPLETED);
    }

    @Test
    void unavailablePortsAndUnsupportedOptionsDoNotReserveAttempts() {
        var dream = ready();
        generator.enabled = false;
        assertThatThrownBy(() -> generate(dream))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ImageErrorCode.UNAVAILABLE);
        generator.enabled = true;
        generator.pending = null;
        storage.enabled = false;
        assertThatThrownBy(() -> generate(dream)).isInstanceOf(BusinessException.class);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from dream_images where user_id=?",
                                Long.class,
                                userId))
                .isZero();
        storage.enabled = true;
        assertThatThrownBy(
                        () ->
                                await(service.generate(
                                        userId,
                                        dream.dreamId(),
                                        new ImageRequest(
                                                dream.revision(), "unknown", null, false, null))))
                .isInstanceOf(BusinessException.class);
        assertThat(generator.calls).hasValue(0);
    }

    @Test
    void preservesResultsAfterDeletionAndHidesForeignOwnership() {
        var dream = ready();
        var result = generate(dream);
        dreams.delete(userId, dream.dreamId(), dream.revision());
        var preserved = transactions.get(userId, result.imageId());
        assertThat(preserved.sourceDeleted()).isTrue();
        assertThat(preserved.status()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(service.downloadUrl(userId, result.imageId()).url()).startsWith("https://");
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@test.com", "다른사용자"));
        assertThatThrownBy(() -> transactions.get(other.getId(), result.imageId()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.downloadUrl(other.getId(), result.imageId()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void discardsSourceEditsAndKeepsTitleOnlyEdits() {
        var dream = ready();
        generator.action =
                input -> {
                    var edit = new DreamUpdateRequest();
                    edit.setRevision(dream.revision());
                    edit.setTitle("새 제목");
                    dreams.update(userId, dream.dreamId(), edit);
                    return ImagePayloadTest.png();
                };
        var first = generate(dream);
        assertThat(first.status()).isEqualTo(GenerationStatus.COMPLETED);
        var current = dreams.get(userId, dream.dreamId());
        generator.action =
                input -> {
                    var edit = new DreamUpdateRequest();
                    edit.setRevision(current.revision());
                    edit.setOriginalText("다른 원문");
                    dreams.update(userId, current.dreamId(), edit);
                    return ImagePayloadTest.png();
                };
        var failed =
                await(service.generate(
                        userId,
                        current.dreamId(),
                        new ImageRequest(
                                current.revision(),
                                "test-style",
                                null,
                                true,
                                first.imageVersion())));
        assertThat(failed.failureCode()).isEqualTo("SOURCE_CHANGED");
        assertThat(failed.hasPreviousResult()).isTrue();
        assertThat(storage.stored).hasSize(1);
        assertThat(storage.deleted).hasSize(1);
    }

    @Test
    void discardsDeletionDuringGeneration() {
        var dream = ready();
        generator.action =
                input -> {
                    dreams.delete(userId, dream.dreamId(), dream.revision());
                    return ImagePayloadTest.png();
                };
        var failed = generate(dream);
        assertThat(failed.failureCode()).isEqualTo("SOURCE_DELETED");
        assertThat(failed.sourceDeleted()).isTrue();
        assertThat(storage.stored).isEmpty();
        assertThat(storage.deleted).hasSize(1);
    }

    @Test
    void discardsStoryChangesAndRequiresRegenerationForOldCompletedImage() {
        var dream = ready();
        var first = generate(dream);
        var story = storyTransactions.latest(userId, dream.dreamId());
        storyteller.output = STORY.replace("바다를 보았다.", "바닷가를 바라보았다.");
        await(
                stories.generate(
                        userId,
                        dream.dreamId(),
                        new StoryRequest(dream.revision(), true, story.storyVersion())));
        assertThat(transactions.get(userId, first.imageId()).storyChanged()).isTrue();
        assertThatThrownBy(() -> generate(dream))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ImageErrorCode.REGENERATION_REQUIRED);
        generator.action =
                input -> {
                    var latest = storyTransactions.latest(userId, dream.dreamId());
                    storyteller.output = STORY;
                    await(
                            stories.generate(
                                    userId,
                                    dream.dreamId(),
                                    new StoryRequest(
                                            dream.revision(), true, latest.storyVersion())));
                    return ImagePayloadTest.png();
                };
        var failed =
                await(service.generate(
                        userId,
                        dream.dreamId(),
                        new ImageRequest(
                                dream.revision(), "test-style", null, true, first.imageVersion())));
        assertThat(failed.failureCode()).isEqualTo("STORY_CHANGED");
        assertThat(failed.hasPreviousResult()).isTrue();
    }

    @Test
    void duplicateRequestsReuseProcessingAndExpiredAttemptCannotOverwriteSuccess()
            throws Exception {
        var dream = ready();
        var firstCall = new CompletableFuture<byte[]>();
        generator.pending = firstCall;
        var running = service.generate(userId, dream.dreamId(), request(dream));
        try {
            assertThat(running).isNotDone();
            var pending = generate(dream);
            assertThat(pending.status()).isEqualTo(GenerationStatus.PROCESSING);
            assertThat(generator.calls).hasValue(1);
            jdbc.update(
                    "update dream_images set lease_until=? where id=?",
                    java.sql.Timestamp.from(Instant.EPOCH), pending.imageId());
            generator.pending = null;
            var completed = generate(dream);
            firstCall.complete(ImagePayloadTest.png());
            assertThat(await(running).status()).isEqualTo(GenerationStatus.COMPLETED);
            assertThat(transactions.get(userId, completed.imageId())).isEqualTo(completed);
            assertThat(storage.stored).hasSize(1);
            assertThat(storage.deleted).hasSize(1);
        } finally {
            firstCall.complete(ImagePayloadTest.png());
            await(running);
        }
    }

    @Test
    void fullCapacityAllowsReuseAndRejectsNewReservationWithoutMutatingIt() {
        var today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        var first = ready(today);
        var second = ready(today.minusDays(1));
        var third = ready(today.minusDays(2));
        var response = new CompletableFuture<byte[]>();
        generator.pending = response;
        var runningFirst = service.generate(userId, first.dreamId(), request(first));
        var runningSecond = service.generate(userId, second.dreamId(), request(second));
        try {
            assertThat(runningFirst).isNotDone();
            assertThat(runningSecond).isNotDone();
            assertThat(generate(first).status()).isEqualTo(GenerationStatus.PROCESSING);
            assertThatThrownBy(() -> service.generate(userId, third.dreamId(), request(third)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ImageErrorCode.BUSY);
            assertThat(generator.calls).hasValue(2);
            assertThatThrownBy(() -> transactions.latest(userId, third.dreamId()))
                    .isInstanceOf(BusinessException.class);
            response.complete(ImagePayloadTest.png());
            var completed = await(runningFirst);
            await(runningSecond);
            generator.pending = null;
            assertThat(generate(third).status()).isEqualTo(GenerationStatus.COMPLETED);
            assertThat(generate(first)).isEqualTo(completed);
        } finally {
            response.complete(ImagePayloadTest.png());
            await(runningFirst);
            await(runningSecond);
        }
    }

    @Test
    void expiredAttemptIsFailedAndUploadedFileIsRemoved() {
        var dream = ready();
        generator.action =
                input -> {
                    jdbc.update(
                            "update dream_images set lease_until=? where id=?",
                            java.sql.Timestamp.from(Instant.EPOCH),
                            input.imageId());
                    return ImagePayloadTest.png();
                };
        assertThat(generate(dream).failureCode()).isEqualTo("ATTEMPT_EXPIRED");
        assertThat(storage.stored).isEmpty();
    }

    @Test
    void databaseFailureRollsBackMetadataCleansUploadAndAllowsRetry() {
        var dream = ready();
        var first = generate(dream);
        jdbc.execute(
                "alter table dream_images add constraint image_test_storage check (id <> "
                        + first.imageId()
                        + " or status <> 'COMPLETED' or style <> 'other-style')");
        try {
            assertThatThrownBy(
                            () ->
                                    await(service.generate(
                                            userId,
                                            dream.dreamId(),
                                            new ImageRequest(
                                                    dream.revision(),
                                                    "other-style",
                                                    null,
                                                    true,
                                                    first.imageVersion()))))
                    .isInstanceOf(BusinessException.class);
            var failed = transactions.get(userId, first.imageId());
            assertThat(failed.failureCode()).isEqualTo("PERSISTENCE_FAILED");
            assertThat(failed.resultStyle()).isEqualTo("test-style");
            assertThat(failed.hasPreviousResult()).isTrue();
            assertThat(storage.stored).hasSize(1);
            assertThat(storage.deleted).hasSize(1);
            jdbc.execute("alter table dream_images drop constraint image_test_storage");
            assertThat(
                            await(service.generate(
                                            userId,
                                            dream.dreamId(),
                                            new ImageRequest(
                                                    dream.revision(),
                                                    "other-style",
                                                    null,
                                                    true,
                                                    failed.imageVersion())))
                                    .status())
                    .isEqualTo(GenerationStatus.COMPLETED);
        } finally {
            jdbc.execute("alter table dream_images drop constraint if exists image_test_storage");
        }
    }

    @Test
    void changedSourceCannotReuseOldImageWithoutCurrentAnalysisAndStory() {
        var dream = ready();
        var first = generate(dream);
        var edit = new DreamUpdateRequest();
        edit.setRevision(dream.revision());
        edit.setOriginalText("바다를 오래 바라보았다");
        dreams.update(userId, dream.dreamId(), edit);
        var current = dreams.get(userId, dream.dreamId());
        assertThat(transactions.get(userId, first.imageId()).sourceChanged()).isTrue();
        assertThatThrownBy(() -> generate(current))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ImageErrorCode.STORY_REQUIRED);
        assertThat(generator.calls).hasValue(1);
        assertThat(storage.stored).hasSize(1);
    }

    @Test
    void draftMissingStoryStaleRevisionAndForeignDreamNeverCallProvider() {
        var draft =
                dreams.create(
                        userId,
                        new DreamCreateRequest(LocalDate.now(ZoneId.of("Asia/Seoul")), "원문"));
        assertThatThrownBy(() -> generate(draft)).isInstanceOf(BusinessException.class);
        var completed =
                dreams.complete(
                        userId,
                        draft.dreamId(),
                        new DreamEmotionsRequest(draft.revision(), List.of(DreamEmotion.HAPPY)));
        assertThatThrownBy(() -> generate(completed))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ImageErrorCode.STORY_REQUIRED);
        await(analyses.analyze(userId, completed.dreamId(), completed.revision()));
        var dream = dreams.get(userId, completed.dreamId());
        await(
                stories.generate(
                        userId, dream.dreamId(), new StoryRequest(dream.revision(), false, null)));
        assertThatThrownBy(
                        () ->
                                await(service.generate(
                                        userId,
                                        dream.dreamId(),
                                        new ImageRequest(-1L, "test-style", null, false, null))))
                .isInstanceOf(BusinessException.class);
        var other = users.saveAndFlush(User.create(UUID.randomUUID() + "@test.com", "다른사용자"));
        assertThatThrownBy(() -> await(service.generate(other.getId(), dream.dreamId(), request(dream))))
                .isInstanceOf(BusinessException.class);
        assertThat(generator.calls).hasValue(0);
    }

    @Test
    void apiEnforcesAuthenticationOwnershipValidationAndNoStore() throws Exception {
        var dream = ready();
        var client = HttpClient.newHttpClient();
        String access = tokens.login(userId).response().accessToken();
        String body = "{\"revision\":" + dream.revision() + ",\"style\":\"test-style\"}";
        var request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://localhost:"
                                                + port
                                                + "/api/v1/dreams/"
                                                + dream.dreamId()
                                                + "/image"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + access)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("COMPLETED").doesNotContain("storageKey");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        var anonymous =
                client.send(
                        HttpRequest.newBuilder(request.uri()).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        var invalid =
                client.send(
                        HttpRequest.newBuilder(request.uri())
                                .header("Content-Type", "application/json")
                                .header("Authorization", "Bearer " + access)
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"revision\":0,\"style\":\"test-style\",\"regenerate\":true}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(invalid.statusCode()).isEqualTo(400);
    }
}
