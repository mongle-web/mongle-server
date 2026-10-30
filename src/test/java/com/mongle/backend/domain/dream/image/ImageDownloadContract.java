package com.mongle.backend.domain.dream.image;

import static com.mongle.backend.domain.dream.gateway.DreamAiTestAwait.await;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.credit.*;
import com.mongle.backend.domain.dream.analysis.*;
import com.mongle.backend.domain.dream.dto.*;
import com.mongle.backend.domain.dream.entity.DreamEmotion;
import com.mongle.backend.domain.dream.service.DreamService;
import com.mongle.backend.domain.dream.story.*;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.common.GenerationStatus;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** H2/HTTP와 실제 MySQL에서 같은 소유권·일괄 조회·성공 파일 보존 계약을 검증한다. */
abstract class ImageDownloadContract {
    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        @Primary
        StructureGenerator batchStructure() {
            return input -> CompletableFuture.completedFuture(DreamImageIntegrationTest.STRUCTURE);
        }

        @Bean
        @Primary
        StoryGenerator batchStory() {
            return input -> CompletableFuture.completedFuture(DreamImageIntegrationTest.STORY);
        }

        @Bean
        @Primary
        DreamImageIntegrationTest.FakeGenerator batchGenerator() {
            return new DreamImageIntegrationTest.FakeGenerator();
        }

        @Bean
        @Primary
        BatchStore batchStore() {
            return new BatchStore();
        }
    }

    static class BatchStore extends DreamImageIntegrationTest.FakeStore {
        final List<String> signed = new CopyOnWriteArrayList<>();
        volatile Consumer<String> beforeSign = key -> {};
        volatile Function<String, DownloadUrl> response = this::normal;

        DownloadUrl normal(String key) {
            return new DownloadUrl(
                    "https://images.test/" + key + "?signature=test-signature",
                    Instant.now().plusSeconds(240));
        }

        @Override
        public DownloadUrl temporaryUrl(String key, Duration lifetime) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(lifetime).isEqualTo(Duration.ofMinutes(5));
            signed.add(key);
            beforeSign.accept(key);
            return response.apply(key);
        }
    }

    @Autowired UserRepository users;
    @Autowired DreamService dreams;
    @Autowired DreamStructureService analyses;
    @Autowired DreamStoryService stories;
    @Autowired DreamImageService images;
    @Autowired ImageTransactions imageTransactions;
    @Autowired ImageDownloadService downloads;
    @Autowired CreditService credits;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired EntityManagerFactory emf;
    @Autowired BatchStore storage;
    @Autowired DreamImageIntegrationTest.FakeGenerator generator;
    Long user;
    int dayOffset;

    @BeforeEach
    void setupBatch() {
        user = member();
        dayOffset = 0;
        storage.enabled = true;
        storage.failPut = storage.failDelete = false;
        storage.stored.clear();
        storage.deleted.clear();
        storage.signed.clear();
        storage.afterPut = key -> {};
        storage.beforeSign = key -> {};
        storage.response = storage::normal;
        generator.calls.set(0);
        generator.pending = null;
        generator.enabled = true;
        generator.action = input -> ImagePayloadTest.png();
    }

    Long member() {
        Long id = users.saveAndFlush(User.create(UUID.randomUUID() + "@batch.test", "테스터")).getId();
        credits.grant(id, CreditSource.PROMOTION, "batch-test:" + id, 300);
        return id;
    }

    DreamResponse ready(Long owner) {
        var dream =
                dreams.create(
                        owner,
                        new DreamCreateRequest(
                                LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(dayOffset++),
                                "바다를 보았다"));
        dream =
                dreams.complete(
                        owner,
                        dream.dreamId(),
                        new DreamEmotionsRequest(dream.revision(), List.of(DreamEmotion.HAPPY)));
        await(analyses.analyze(owner, dream.dreamId(), dream.revision()));
        dream = dreams.get(owner, dream.dreamId());
        await(
                stories.generate(
                        owner, dream.dreamId(), new StoryRequest(dream.revision(), false, null)));
        return dreams.get(owner, dream.dreamId());
    }

    ImageResponse generated(Long owner) {
        var dream = ready(owner);
        return await(
                images.generate(
                        owner,
                        dream.dreamId(),
                        new ImageRequest(dream.revision(), "test-style", null, false, null)));
    }

    String key(Long image) {
        return jdbc.queryForObject(
                "select storage_key from dream_images where id=?", String.class, image);
    }

    @Test
    void metadataUsesOneQueryAndHidesForeignDeletedDetachedAndMissingSuccessfulAssets() {
        var a = generated(user);
        var b = generated(user);
        var foreign = generated(member());
        var deleted = generated(user);
        dreams.delete(user, deleted.dreamId(), dreams.get(user, deleted.dreamId()).revision());
        var detached = generated(user);
        jdbc.update("update dream_analyses set dream_id=null where id=?", detached.analysisId());
        var pendingDream = ready(user);
        var pending =
                imageTransactions.begin(
                        user,
                        pendingDream.dreamId(),
                        new ImageRequest(pendingDream.revision(), "test-style", null, false, null),
                        true);
        var failedDream = ready(user);
        var failed =
                imageTransactions.begin(
                        user,
                        failedDream.dreamId(),
                        new ImageRequest(failedDream.revision(), "test-style", null, false, null),
                        true);
        imageTransactions.fail(failed.input(), "CALL_FAILED");
        var requested =
                List.of(
                        b.imageId(),
                        a.imageId(),
                        b.imageId(),
                        foreign.imageId(),
                        deleted.imageId(),
                        detached.imageId(),
                        pending.response().imageId(),
                        failed.response().imageId(),
                        Long.MAX_VALUE);
        var stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        var result = downloads.downloadUrls(user, requested);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
        assertThat(result.items())
                .extracting(ImageDownloadResponse.Item::imageId)
                .containsExactly(
                        b.imageId(),
                        a.imageId(),
                        foreign.imageId(),
                        deleted.imageId(),
                        detached.imageId(),
                        pending.response().imageId(),
                        failed.response().imageId(),
                        Long.MAX_VALUE);
        assertThat(result.items().subList(0, 2))
                .allSatisfy(
                        item ->
                                assertThat(item.status())
                                        .isEqualTo(ImageDownloadResponse.Status.SUCCESS));
        assertThat(result.items().subList(2, result.items().size()))
                .allSatisfy(
                        item -> ImageDownloadServiceTest.assertFailure(item, null, "IMAGE_404_1"));
        assertThat(storage.signed).containsExactly(key(b.imageId()), key(a.imageId()));
        var ids = new ArrayList<Long>();
        ids.add(a.imageId());
        for (int i = 1; i < 31; i++) ids.add(Long.MAX_VALUE - i);
        stats.clear();
        var full = downloads.downloadUrls(user, ids);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
        assertThat(full.items()).hasSize(31);
        imageTransactions.fail(pending.input(), "TEST_FINISHED");
    }

    @Test
    void previousSuccessfulFileSurvivesProcessingFailureAndSwitchesOnlyAfterSuccess() {
        var first = generated(user);
        String priorKey = key(first.imageId());
        generator.pending = new CompletableFuture<>();
        var attempt =
                images.generate(
                        user,
                        first.dreamId(),
                        new ImageRequest(
                                dreams.get(user, first.dreamId()).revision(),
                                "test-style",
                                null,
                                true,
                                first.imageVersion()));
        assertThat(imageTransactions.get(user, first.imageId()).status())
                .isEqualTo(GenerationStatus.PROCESSING);
        var processing = downloads.downloadUrls(user, List.of(first.imageId())).items().getFirst();
        assertThat(processing.imageVersion())
                .isEqualTo(imageTransactions.get(user, first.imageId()).imageVersion());
        assertThat(processing.url()).contains(priorKey);
        generator.pending.completeExceptionally(new IllegalStateException("test provider failure"));
        var failed = await(attempt);
        assertThat(failed.status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(downloads.downloadUrls(user, List.of(first.imageId())).items().getFirst().url())
                .contains(priorKey);
        generator.pending = null;
        var replaced =
                await(
                        images.generate(
                                user,
                                first.dreamId(),
                                new ImageRequest(
                                        dreams.get(user, first.dreamId()).revision(),
                                        "test-style",
                                        null,
                                        true,
                                        failed.imageVersion())));
        var current = downloads.downloadUrls(user, List.of(first.imageId())).items().getFirst();
        assertThat(current.imageVersion()).isEqualTo(replaced.imageVersion());
        assertThat(current.url()).contains(key(first.imageId())).doesNotContain(priorKey);
        dreams.delete(user, first.dreamId(), dreams.get(user, first.dreamId()).revision());
        ImageDownloadServiceTest.assertFailure(
                downloads.downloadUrls(user, List.of(first.imageId())).items().getFirst(),
                null,
                "IMAGE_404_1");
    }

    @Test
    void partialFailureDoesNotChangeCreditImagesOrCallGeneratorsOrFileWrites() {
        var a = generated(user);
        var b = generated(user);
        String failingKey = key(a.imageId());
        var before =
                jdbc.queryForList("select * from dream_images where user_id=? order by id", user);
        var ledger =
                jdbc.queryForList("select * from credit_ledger where user_id=? order by id", user);
        var balance = credits.balance(user);
        var stored = new HashMap<>(storage.stored);
        int calls = generator.calls.get();
        storage.response =
                key -> {
                    if (key.equals(failingKey))
                        throw new IllegalStateException("internal-message ?signature=secret");
                    return storage.normal(key);
                };
        var result = downloads.downloadUrls(user, List.of(a.imageId(), b.imageId()));
        ImageDownloadServiceTest.assertFailure(
                result.items().get(0), a.imageVersion(), "IMAGE_502_2");
        assertThat(result.items().get(1).status()).isEqualTo(ImageDownloadResponse.Status.SUCCESS);
        assertThat(result.toString()).doesNotContain("internal-message", "signature", failingKey);
        assertThat(
                        jdbc.queryForList(
                                "select * from dream_images where user_id=? order by id", user))
                .isEqualTo(before);
        assertThat(
                        jdbc.queryForList(
                                "select * from credit_ledger where user_id=? order by id", user))
                .isEqualTo(ledger);
        assertThat(credits.balance(user)).isEqualTo(balance);
        assertThat(generator.calls).hasValue(calls);
        assertThat(storage.stored).containsExactlyInAnyOrderEntriesOf(stored);
        assertThat(storage.deleted).isEmpty();
    }

    @Test
    void signingSuspendsCallerTransactionAndDoesNotHoldALockAgainstConcurrentDeletion() {
        var image = generated(user);
        storage.beforeSign =
                key -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    try (var executor = Executors.newSingleThreadExecutor()) {
                        try {
                            executor.submit(
                                            () ->
                                                    dreams.delete(
                                                            user,
                                                            image.dreamId(),
                                                            dreams.get(user, image.dreamId())
                                                                    .revision()))
                                    .get(5, TimeUnit.SECONDS);
                        } catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                    }
                };
        transactions.executeWithoutResult(
                status -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isTrue();
                    var snapshot =
                            downloads
                                    .downloadUrls(user, List.of(image.imageId()))
                                    .items()
                                    .getFirst();
                    assertThat(snapshot.status()).isEqualTo(ImageDownloadResponse.Status.SUCCESS);
                    assertThat(snapshot.imageVersion()).isEqualTo(image.imageVersion());
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isTrue();
                });
        ImageDownloadServiceTest.assertFailure(
                downloads.downloadUrls(user, List.of(image.imageId())).items().getFirst(),
                null,
                "IMAGE_404_1");
    }
}
