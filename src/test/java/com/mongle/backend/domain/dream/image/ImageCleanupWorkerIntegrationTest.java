package com.mongle.backend.domain.dream.image;

import static com.mongle.backend.domain.dream.gateway.DreamAiTestAwait.await;
import static org.assertj.core.api.Assertions.*;
import com.mongle.backend.domain.dream.analysis.*;
import com.mongle.backend.domain.dream.dto.*;
import com.mongle.backend.domain.dream.entity.DreamEmotion;
import com.mongle.backend.domain.dream.service.DreamService;
import com.mongle.backend.domain.dream.story.*;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.system.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

@SpringBootTest(properties = {
        "mongle.credit.enabled=true", "mongle.credit.dream-image-generation=2", "mongle.credit.dream-image-regeneration=3",
        "spring.datasource.url=jdbc:h2:mem:image-cleanup-worker;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "mongle.image.styles[0]=test-style", "mongle.image.cleanup.enabled=false",
        "mongle.image.cleanup.lease=5s", "mongle.image.cleanup.retry-base-delay=1s",
        "mongle.image.cleanup.retry-max-delay=4s", "mongle.image.cleanup.max-attempts=3"})
@ActiveProfiles("test")
@Import(ImageCleanupWorkerIntegrationTest.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class ImageCleanupWorkerIntegrationTest {
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return now.get(); }
        void advance(Duration duration) { now.updateAndGet(i -> i.plus(duration)); }
    }
    static class Store implements ImageAssetStore {
        volatile boolean enabled = true;
        volatile Consumer<String> action = key -> {};
        volatile Consumer<String> afterDelete = key -> {};
        final Set<String> objects = ConcurrentHashMap.newKeySet();
        final AtomicInteger calls = new AtomicInteger();
        @Override public boolean available() { return enabled; }
        @Override public void put(String key, ImagePayload payload) { objects.add(key); }
        @Override public void delete(String key) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calls.incrementAndGet(); action.accept(key); objects.remove(key); afterDelete.accept(key);
        }
        @Override public DownloadUrl temporaryUrl(String key, Duration ttl) { throw new UnsupportedOperationException(); }
    }
    @TestConfiguration
    static class Config {
        @Bean @Primary MutableClock cleanupClock() {
            var clock = new MutableClock(); clock.now.set(Instant.parse("2026-10-18T03:00:00Z")); return clock;
        }
        @Bean @Primary Store cleanupStore() { return new Store(); }
        @Bean @Primary ImageGenerator cleanupGenerator() { return input -> CompletableFuture.completedFuture(ImagePayloadTest.png()); }
        @Bean @Primary StructureGenerator cleanupStructure() {
            return input -> CompletableFuture.completedFuture(DreamImageIntegrationTest.STRUCTURE);
        }
        @Bean @Primary StoryGenerator cleanupStory() {
            return input -> CompletableFuture.completedFuture(DreamImageIntegrationTest.STORY);
        }
    }

    @Autowired com.mongle.backend.domain.credit.CreditService credits;
    @Autowired ImageCleanupWorkTransactions work;
    @Autowired ImageCleanupTransactions registration;
    @Autowired ImageCleanupRegistration mandatoryRegistration;
    @Autowired ImageCleanupRequestRepository requests;
    @Autowired ImageCleanupProperties properties;
    @Autowired DreamService dreams;
    @Autowired DreamStructureService analyses;
    @Autowired DreamStoryService stories;
    @Autowired DreamImageService images;
    @Autowired ImageTransactions imageTransactions;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired MutableClock clock;
    @Autowired Store store;
    ImageCleanupWorker worker;
    Long user;

    @BeforeEach
    void setup() {
        requests.deleteAll();
        clock.now.set(Instant.parse("2026-10-18T03:00:00Z"));
        user = users.saveAndFlush(User.create(UUID.randomUUID()+"@test.com", "정리검증")).getId();
        credits.grant(user, com.mongle.backend.domain.credit.CreditSource.SIGNUP, "image-test:"+user, 1000);
        store.enabled = true; store.action = key -> {}; store.afterDelete = key -> {}; store.objects.clear(); store.calls.set(0);
        worker = new ImageCleanupWorker(work, store, enabled(true));
    }
    @AfterEach void close() { worker.close(); }
    ImageCleanupProperties enabled(boolean enabled) {
        return new ImageCleanupProperties(enabled, properties.pollInterval(), properties.batchSize(),
                properties.maxConcurrentCalls(), properties.lease(), properties.retryBaseDelay(),
                properties.retryMaxDelay(), properties.maxAttempts());
    }
    ImageCleanupRequest request(Long id) { return requests.findById(id).orElseThrow(); }
    Long enqueue(String key) { return enqueue(999L, key); }
    Long enqueue(Long imageId, String key) {
        registration.recordDiscardedUpload(user, imageId, key);
        return requests.findAll().stream().filter(r -> r.getStorageKey().equals(key)).findFirst().orElseThrow().getId();
    }
    void drained() throws Exception {
        long end = System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while (worker.activeCount()!=0 && System.nanoTime()<end) Thread.sleep(10);
        assertThat(worker.activeCount()).isZero();
    }
    void run() throws Exception { worker.poll(); drained(); }
    DreamResponse ready() {
        var draft = dreams.create(user, new DreamCreateRequest(LocalDate.of(2026,10,18), "바다를 보았다"));
        var dream = dreams.complete(user, draft.dreamId(), new DreamEmotionsRequest(draft.revision(), List.of(DreamEmotion.HAPPY)));
        await(analyses.analyze(user, dream.dreamId(), dream.revision()));
        dream = dreams.get(user, dream.dreamId());
        await(stories.generate(user, dream.dreamId(), new StoryRequest(dream.revision(), false, null)));
        return dream;
    }
    ImageRequest imageRequest(DreamResponse dream) { return new ImageRequest(dream.revision(), "test-style", null, false, null); }

    @Test
    void deletedDreamFileIsRemovedOnlyAfterCommitWithoutAffectingOtherObjects() throws Exception {
        var dream = ready();
        var result = await(images.generate(user, dream.dreamId(), imageRequest(dream)));
        String key = imageTransactions.assetKey(user, result.imageId());
        store.objects.add("unrelated-object");
        dreams.delete(user, dream.dreamId(), dream.revision());
        var pending = requests.findAll().getFirst();
        assertThat(pending.getStatus()).isEqualTo(ImageCleanupRequest.Status.PENDING);
        run();
        assertThat(request(pending.getId()).getStatus()).isEqualTo(ImageCleanupRequest.Status.COMPLETED);
        assertThat(request(pending.getId()).getCompletedAt()).isEqualTo(clock.instant());
        assertThat(store.objects).doesNotContain(key).contains("unrelated-object");
        assertThatThrownBy(() -> imageTransactions.get(user, result.imageId())).isInstanceOf(com.mongle.backend.global.error.BusinessException.class);
        run(); assertThat(store.calls).hasValue(1);
    }

    @Test
    void unavailableOrDisabledWorkerDoesNotConsumeAttemptsOrFinishRequests() throws Exception {
        var id = enqueue("unavailable-key");
        store.enabled = false; run();
        assertThat(request(id).getAttemptCount()).isZero();
        assertThat(request(id).getStatus()).isEqualTo(ImageCleanupRequest.Status.PENDING);
        store.enabled = true;
        try (var disabled = new ImageCleanupWorker(work, store, enabled(false))) { disabled.poll(); }
        assertThat(store.calls).hasValue(0);
        run(); assertThat(request(id).getStatus()).isEqualTo(ImageCleanupRequest.Status.COMPLETED);
    }

    @Test
    void retriesWithBackoffThenHoldsAtConfiguredLimitAndLogsNoExternalSecrets(CapturedOutput output) throws Exception {
        var id = enqueue("private-storage-key-not-for-logs");
        store.action = key -> { throw new IllegalStateException("https://secret.example/?signature=secret-provider-body"); };
        run();
        assertThat(request(id).getStatus()).isEqualTo(ImageCleanupRequest.Status.RETRY_WAIT);
        assertThat(request(id).getNextRunAt()).isEqualTo(clock.instant().plusSeconds(1));
        run(); assertThat(store.calls).hasValue(1);
        clock.advance(Duration.ofSeconds(1)); run();
        assertThat(request(id).getNextRunAt()).isEqualTo(clock.instant().plusSeconds(2));
        clock.advance(Duration.ofSeconds(2)); run();
        assertThat(request(id).getStatus()).isEqualTo(ImageCleanupRequest.Status.HELD);
        assertThat(request(id).getAttemptCount()).isEqualTo(3);
        assertThat(request(id).getCompletedAt()).isNull();
        clock.advance(Duration.ofHours(1)); run(); assertThat(store.calls).hasValue(3);
        assertThat(output.getAll()).doesNotContain("private-storage-key-not-for-logs", "secret.example", "secret-provider-body");
        enqueue("private-storage-key-not-for-logs");
        assertThat(request(id).getStatus()).isEqualTo(ImageCleanupRequest.Status.PENDING);
        assertThat(request(id).getAttemptCount()).isZero();
    }

    @Test
    void expiredClaimRecoversAndOldOrExpiredCompletionsCannotOverwriteIt() {
        var id = enqueue("expired-key");
        var first = work.claim(id).orElseThrow();
        assertThat(work.claim(id)).isEmpty();
        clock.advance(Duration.ofSeconds(6));
        work.complete(first);
        assertThat(request(id).getStatus()).isEqualTo(ImageCleanupRequest.Status.PROCESSING);
        var next = work.claim(id).orElseThrow();
        assertThat(next.token()).isNotEqualTo(first.token());
        work.complete(first); work.failed(first);
        assertThat(request(id).getClaimToken()).isEqualTo(next.token());
        work.complete(next);
        assertThat(request(id).getStatus()).isEqualTo(ImageCleanupRequest.Status.COMPLETED);
        assertThat(request(id).getAttemptCount()).isEqualTo(2);
        assertThat(next.toString()).doesNotContain("expired-key", next.token());
    }

    @Test
    void exhaustedExpiredClaimsAreHeldAndUnavailableAfterClaimDoesNotSpendAttempt() {
        var id = enqueue("no-io-key");
        var claim = work.claim(id).orElseThrow();
        work.postpone(claim);
        assertThat(request(id).getAttemptCount()).isZero();
        clock.advance(Duration.ofSeconds(1));
        for (int i=0;i<3;i++) { assertThat(work.claim(id)).isPresent(); clock.advance(Duration.ofSeconds(6)); }
        assertThat(work.claim(id)).isEmpty();
        assertThat(request(id).getStatus()).isEqualTo(ImageCleanupRequest.Status.HELD);
        assertThat(request(id).getFailureCode()).isEqualTo("RETRY_EXHAUSTED");
        assertThat(store.calls).hasValue(0);
    }

    @Test
    void duplicateAndCompletedOrInFlightReregistrationAreCoalescedWithoutLostWork() {
        var id = enqueue("repeat-key");
        assertThat(enqueue("repeat-key")).isEqualTo(id);
        assertThat(requests.count()).isEqualTo(1);
        var first = work.claim(id).orElseThrow();
        enqueue("repeat-key");
        assertThat(request(id).isRequeueRequested()).isTrue();
        work.complete(first);
        assertThat(request(id).getStatus()).isEqualTo(ImageCleanupRequest.Status.PENDING);
        var next = work.claim(id).orElseThrow(); work.complete(first);
        assertThat(request(id).getClaimToken()).isEqualTo(next.token());
        work.complete(next);
        enqueue("repeat-key");
        assertThat(requests.count()).isEqualTo(1);
        assertThat(request(id).getStatus()).isEqualTo(ImageCleanupRequest.Status.PENDING);
        assertThat(request(id).getDeletionStartedAt()).isNotNull();
    }

    @Test
    void currentAndPreviousSuccessfulFileReferencesAreProtectedThenDeletionReusesTheRequest() throws Exception {
        var dream = ready();
        var image = await(images.generate(user, dream.dreamId(), imageRequest(dream)));
        String key = imageTransactions.assetKey(user, image.imageId());
        var id = enqueue(image.imageId(), key); run();
        assertThat(request(id).getFailureCode()).isEqualTo("REFERENCED");
        assertThat(request(id).getAttemptCount()).isZero();
        assertThat(request(id).getDeletionStartedAt()).isNull();
        jdbc.update("update dream_images set status='FAILED' where id=?", image.imageId());
        clock.advance(Duration.ofSeconds(1)); run();
        assertThat(store.calls).hasValue(0); assertThat(store.objects).contains(key);
        dreams.delete(user, dream.dreamId(), dream.revision());
        clock.advance(Duration.ofSeconds(1)); run();
        assertThat(requests.count()).isEqualTo(1);
        assertThat(request(id).getStatus()).isEqualTo(ImageCleanupRequest.Status.COMPLETED);
        assertThat(store.objects).doesNotContain(key);
    }

    @Test
    void activeAttemptIsProtectedAndCanStillPublishBeforeAnyDeletionStarts() throws Exception {
        var dream = ready();
        var reservation = imageTransactions.begin(user, dream.dreamId(), imageRequest(dream), true);
        var input = reservation.input();
        String key = "dream-images/"+user+"/"+input.imageId()+"/"+input.attemptId()+".png";
        var id = enqueue(input.imageId(), key); run();
        assertThat(request(id).getFailureCode()).isEqualTo("ACTIVE_ATTEMPT");
        assertThat(request(id).getDeletionStartedAt()).isNull();
        var completion = imageTransactions.finish(input, key, ImagePayload.parse(ImagePayloadTest.png()));
        assertThat(completion.stored()).isTrue();
        // 완료 재전송은 성공 파일을 보상 삭제 대상으로 돌리지 않는다.
        assertThat(imageTransactions.finish(input, key, ImagePayload.parse(ImagePayloadTest.png())).stored()).isTrue();
        clock.advance(Duration.ofSeconds(1)); run(); assertThat(store.calls).hasValue(0);
        assertThat(request(id).getFailureCode()).isEqualTo("REFERENCED");
    }

    @Test
    void deletionFencePreventsLateAdoptionAndPreparationRechecksReferences() {
        var dream = ready();
        var input = imageTransactions.begin(user, dream.dreamId(), imageRequest(dream), true).input();
        String key = "dream-images/"+user+"/"+input.imageId()+"/"+input.attemptId()+".png";
        var id = enqueue(input.imageId(), key);
        jdbc.update("update dream_images set lease_until=? where id=?", databaseTime(clock.instant().minusSeconds(1)), input.imageId());
        var claim = work.claim(id).orElseThrow();
        // 비정상 lease 복구가 있더라도 삭제 시작한 키를 공개 결과로 채택하지 않는다.
        jdbc.update("update dream_images set lease_until=? where id=?", databaseTime(clock.instant().plusSeconds(20)), input.imageId());
        var result = imageTransactions.finish(input, key, ImagePayload.parse(ImagePayloadTest.png()));
        assertThat(result.stored()).isFalse();
        assertThat(result.response().failureCode()).isEqualTo("ASSET_DISCARDED");
        // 과거/우회 쓰기 데이터가 생겨도 IO 직전 참조 재검사는 보호한다.
        jdbc.update("update dream_images set storage_key=? where id=?", key, input.imageId());
        assertThat(work.prepare(claim)).isFalse();
        assertThat(request(id).getFailureCode()).isEqualTo("REFERENCED");
    }
    private LocalDateTime databaseTime(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }

    @Test
    void concurrentClaimsUseOneTokenAcrossWorkerInstances() throws Exception {
        var id = enqueue("concurrent-key");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Optional<ImageCleanupWorkTransactions.Claim>> task = () -> { start.await(); return work.claim(id); };
            var a = executor.submit(task); var b = executor.submit(task); start.countDown();
            var outcomes = List.of(a.get(5,TimeUnit.SECONDS), b.get(5,TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(Optional::isPresent).count()).isEqualTo(1);
            assertThat(request(id).getAttemptCount()).isEqualTo(1);
        }
    }

    @Test
    void boundsExternalExecutionAndStopsReservingWhenClosed() throws Exception {
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        store.action = key -> {
            entered.countDown();
            try { assertThat(release.await(5,TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
        };
        for (int i=0;i<5;i++) enqueue("bounded-"+i);
        try {
            worker.poll(); assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            worker.poll(); assertThat(worker.activeCount()).isEqualTo(2);
            assertThat(store.calls).hasValue(2);
            assertThat(requests.findAll().stream().filter(r -> r.getStatus()==ImageCleanupRequest.Status.PENDING).count()).isEqualTo(3);
        } finally { release.countDown(); }
        drained(); worker.close(); worker.poll(); assertThat(store.calls).hasValue(2);
    }

    @Test
    void completionPersistenceFailureRecoversByIdempotentDeleteAfterLeaseExpiry() throws Exception {
        var id = enqueue("persist-failure-key"); store.objects.add("persist-failure-key");
        jdbc.execute("alter table image_cleanup_requests add constraint reject_cleanup_completion check(status<>'COMPLETED')");
        try {
            run();
            assertThat(store.objects).doesNotContain("persist-failure-key");
            assertThat(request(id).getStatus()).isEqualTo(ImageCleanupRequest.Status.PROCESSING);
        } finally { jdbc.execute("alter table image_cleanup_requests drop constraint reject_cleanup_completion"); }
        worker.close();
        worker = new ImageCleanupWorker(work, store, enabled(true));
        clock.advance(Duration.ofSeconds(6)); run();
        assertThat(request(id).getStatus()).isEqualTo(ImageCleanupRequest.Status.COMPLETED);
        assertThat(store.calls).hasValue(2);
    }

    @Test
    void rolledBackRequestsAreInvisibleToTheWorker() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            users.findByIdForUpdate(user).orElseThrow();
            mandatoryRegistration.register(user, null, 999L, "rolled-back-key", ImageCleanupRequest.Reason.DISCARDED_UPLOAD);
            try (var executor = Executors.newSingleThreadExecutor()) {
                try { assertThat(executor.submit(() -> work.candidates(10)).get(5,TimeUnit.SECONDS)).isEmpty(); }
                catch (Exception ex) { throw new AssertionError(ex); }
            }
            status.setRollbackOnly();
        });
        assertThat(requests.count()).isZero();
    }
    @Test
    void reregistrationAfterExternalDeleteBeforeCompletionCannotLoseAReuploadedObject() throws Exception {
        String key = "upload-after-delete";
        var id = enqueue(key); store.objects.add(key);
        var deleted = new CountDownLatch(1); var release = new CountDownLatch(1);
        store.afterDelete = ignored -> {
            deleted.countDown();
            try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
        };
        try {
            worker.poll(); assertThat(deleted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(store.objects).doesNotContain(key);
            store.objects.add(key); enqueue(key);
        } finally { release.countDown(); }
        drained();
        assertThat(request(id).getStatus()).isEqualTo(ImageCleanupRequest.Status.PENDING);
        assertThat(store.objects).contains(key);
        store.afterDelete = ignored -> {};
        run();
        assertThat(request(id).getStatus()).isEqualTo(ImageCleanupRequest.Status.COMPLETED);
        assertThat(store.objects).doesNotContain(key);
        assertThat(store.calls).hasValue(2);
    }

    @Test
    void claimPersistenceFailureDoesNotStartExternalDelete() throws Exception {
        var id = enqueue("claim-rollback");
        jdbc.execute("alter table image_cleanup_requests add constraint reject_cleanup_claim check(status<>'PROCESSING')");
        try {
            run();
            assertThat(request(id).getStatus()).isEqualTo(ImageCleanupRequest.Status.PENDING);
            assertThat(request(id).getAttemptCount()).isZero();
            assertThat(request(id).getDeletionStartedAt()).isNull();
            assertThat(store.calls).hasValue(0);
        } finally { jdbc.execute("alter table image_cleanup_requests drop constraint reject_cleanup_claim"); }
        run(); assertThat(request(id).getStatus()).isEqualTo(ImageCleanupRequest.Status.COMPLETED);
    }

    @Test
    void concurrentRegistrationUsesOneDurableRequest() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Void> task = () -> { start.await(); registration.recordDiscardedUpload(user,999L,"registration-race"); return null; };
            var a = executor.submit(task); var b = executor.submit(task); start.countDown();
            a.get(5, TimeUnit.SECONDS); b.get(5, TimeUnit.SECONDS);
        }
        assertThat(requests.count()).isEqualTo(1);
        assertThat(requests.findAll().getFirst().getStatus()).isEqualTo(ImageCleanupRequest.Status.PENDING);
    }

}
