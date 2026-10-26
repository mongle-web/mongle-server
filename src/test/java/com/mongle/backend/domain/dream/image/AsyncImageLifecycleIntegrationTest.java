package com.mongle.backend.domain.dream.image;

import static com.mongle.backend.domain.dream.gateway.DreamAiTestAwait.await;
import static org.assertj.core.api.Assertions.*;
import com.mongle.backend.domain.auth.service.TokenService;
import com.mongle.backend.domain.dream.analysis.DreamStructureService;
import com.mongle.backend.domain.dream.dto.*;
import com.mongle.backend.domain.dream.entity.DreamEmotion;
import com.mongle.backend.domain.dream.exception.DreamErrorCode;
import com.mongle.backend.domain.dream.generation.*;
import com.mongle.backend.domain.dream.service.DreamService;
import com.mongle.backend.domain.dream.story.*;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** #39 비동기 호출에 #46 보호, #48 삭제, #50 정리/재등록을 연결한 회귀 검증. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mongle.credit.enabled=true", "mongle.credit.dream-image-generation=2", "mongle.credit.dream-image-regeneration=3",
        "spring.datasource.url=jdbc:h2:mem:async-image-lifecycle;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "mongle.image.styles[0]=test-style", "mongle.image.execution.max-concurrent-calls=2",
        "mongle.image.cleanup.enabled=false", "mongle.image.cleanup.retry-base-delay=100ms"})
@ActiveProfiles("test")
@Import(DreamImageIntegrationTest.Config.class)
class AsyncImageLifecycleIntegrationTest {
    @Autowired com.mongle.backend.domain.credit.CreditService credits;
    @Autowired DreamService dreams;
    @Autowired DreamStructureService analyses;
    @Autowired DreamStoryService stories;
    @Autowired DreamGenerationTransactions generation;
    @Autowired DreamImageService images;
    @Autowired ImageTransactions transactions;
    @Autowired ImageGenerationResources resources;
    @Autowired ImageCleanupRequestRepository requests;
    @Autowired ImageCleanupTransactions registration;
    @Autowired ImageCleanupWorkTransactions cleanupWork;
    @Autowired ImageCleanupProperties cleanupProperties;
    @Autowired DreamImageIntegrationTest.FakeGenerator generator;
    @Autowired DreamImageIntegrationTest.FakeStore storage;
    @Autowired UserRepository users;
    @Autowired TokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Value("${local.server.port}") int port;
    Long user;

    @BeforeEach void setup() {
        user = users.saveAndFlush(User.create(UUID.randomUUID()+"@test.com", "통합검증")).getId();
        credits.grant(user, com.mongle.backend.domain.credit.CreditSource.SIGNUP, "image-test:"+user, 1000);
        generator.enabled = true; generator.calls.set(0); generator.pending = null; generator.last = null;
        generator.action = input -> ImagePayloadTest.png();
        storage.enabled = true; storage.failPut = false; storage.failDelete = false;
        storage.afterPut = key -> {}; storage.stored.clear(); storage.deleted.clear();
    }
    @AfterEach void releasePending() { if (generator.pending != null) generator.pending.complete(ImagePayloadTest.png()); }
    DreamResponse ready(int day) {
        var draft = dreams.create(user, new DreamCreateRequest(LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(day), "바다를 보았다"));
        var dream = dreams.complete(user,draft.dreamId(),new DreamEmotionsRequest(draft.revision(),List.of(DreamEmotion.HAPPY)));
        await(analyses.analyze(user,dream.dreamId(),dream.revision()));
        dream = dreams.get(user,dream.dreamId());
        await(stories.generate(user,dream.dreamId(),new StoryRequest(dream.revision(),false,null)));
        jdbc.update("update dream_generation_jobs set status='COMPLETED',stage='STORY' where dream_id=?",dream.dreamId());
        return dreams.get(user,dream.dreamId());
    }
    ImageRequest request(DreamResponse dream) { return new ImageRequest(dream.revision(),"test-style",null,false,null); }
    ImageRequest regenerate(DreamResponse dream, ImageResponse result) { return new ImageRequest(dream.revision(),"test-style",null,true,result.imageVersion()); }
    void expire(Long image) { jdbc.update("update dream_images set lease_until=? where id=?",java.sql.Timestamp.from(Instant.EPOCH),image); }
    void error(Runnable operation, ErrorCode code) {
        assertThatThrownBy(operation::run).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(code);
    }
    void eventually(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime()<end) Thread.sleep(10);
        assertThat(condition.getAsBoolean()).isTrue();
    }
    void slotsReleased() throws Exception {
        eventually(() -> {
            var held = new ArrayList<ImageGenerationResources.Permit>();
            try { held.add(resources.reserve()); held.add(resources.reserve()); return true; }
            catch (RejectedExecutionException ex) { return false; }
            finally { held.forEach(ImageGenerationResources.Permit::close); }
        });
    }
    ImageCleanupWorker worker() {
        var p = cleanupProperties;
        return new ImageCleanupWorker(cleanupWork,storage,new ImageCleanupProperties(true,p.pollInterval(),p.batchSize(),
                p.maxConcurrentCalls(),p.lease(),p.retryBaseDelay(),p.retryMaxDelay(),p.maxAttempts()));
    }
    List<ImageCleanupRequest> ownedRequests() { return requests.findAll().stream().filter(r -> r.getUserId().equals(user)).toList(); }

    @Test
    void pendingImageGuardsOnlyItsDreamAndRunsStorageOnCompletionThreads() throws Exception {
        var a = ready(0); var b = ready(1);
        var source = new CompletableFuture<byte[]>(); generator.pending = source;
        var storedOn = new CompletableFuture<String>(); storage.afterPut = key -> storedOn.complete(Thread.currentThread().getName());
        var call = images.generate(user,a.dreamId(),request(a));
        assertThat(call).isNotDone();
        assertThat(dreams.mutationStatus(user,a.dreamId()).blockingTasks()).contains(DreamMutationStatus.BlockingTask.IMAGE);
        var edit = new DreamUpdateRequest(); edit.setRevision(a.revision()); edit.setTitle("새 제목");
        error(() -> dreams.update(user,a.dreamId(),edit),DreamErrorCode.GENERATION_IN_PROGRESS);
        error(() -> dreams.delete(user,a.dreamId(),a.revision()),DreamErrorCode.GENERATION_IN_PROGRESS);
        var version = generation.get(user,a.dreamId()).generationVersion();
        error(() -> generation.regenerate(user,a.dreamId(),new DreamRegenerationRequest(a.revision(),version)),DreamErrorCode.GENERATION_IN_PROGRESS);
        edit.setRevision(b.revision());
        assertThat(dreams.update(user,b.dreamId(),edit).title()).isEqualTo("새 제목");
        source.complete(ImagePayloadTest.png());
        assertThat(await(call).status()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(await(storedOn)).startsWith("image-result-");
        assertThat(dreams.mutationStatus(user,a.dreamId()).mutable()).isTrue(); slotsReleased();
    }

    @Test
    void cachedSuccessSurvivesNarrativeRegenerationAndFullImageCapacity() {
        var cached = ready(0); var a = ready(1); var b = ready(2);
        var success = await(images.generate(user,cached.dreamId(),request(cached)));
        var source = new CompletableFuture<byte[]>(); generator.pending = source;
        var first = images.generate(user,a.dreamId(),request(a)); var second = images.generate(user,b.dreamId(),request(b));
        try {
            var version = generation.get(user,cached.dreamId()).generationVersion();
            generation.regenerate(user,cached.dreamId(),new DreamRegenerationRequest(cached.revision(),version));
            assertThat(await(images.generate(user,cached.dreamId(),request(cached)))).isEqualTo(success);
            error(() -> images.generate(user,cached.dreamId(),regenerate(cached,success)),DreamErrorCode.GENERATION_IN_PROGRESS);
            assertThat(generator.calls).hasValue(3);
        } finally { source.complete(ImagePayloadTest.png()); await(first); await(second); }
    }

    @Test
    void deletionAfterExpiryQueuesOldSuccessAndRejectedLateUploadForWorker() throws Exception {
        var dream = ready(0);
        var success = await(images.generate(user,dream.dreamId(),request(dream)));
        String oldKey = transactions.assetKey(user,success.imageId());
        var source = new CompletableFuture<byte[]>(); generator.pending = source;
        var call = images.generate(user,dream.dreamId(),regenerate(dream,success));
        expire(success.imageId()); dreams.delete(user,dream.dreamId(),dream.revision());
        storage.failDelete = true; source.complete(ImagePayloadTest.png());
        error(() -> await(call),ImageErrorCode.NOT_FOUND);
        assertThat(ownedRequests().stream().map(ImageCleanupRequest::getReason)).containsExactlyInAnyOrder(
                ImageCleanupRequest.Reason.DREAM_DELETED,ImageCleanupRequest.Reason.DISCARDED_UPLOAD);
        assertThat(storage.stored).containsKey(oldKey).hasSize(2);
        assertThat(jdbc.queryForObject("select count(*) from dream_images where user_id=?",Long.class,user)).isZero();
        error(() -> images.downloadUrl(user,success.imageId()),ImageErrorCode.NOT_FOUND);
        slotsReleased(); storage.failDelete = false;
        try (var worker = worker()) {
            worker.poll(); eventually(() -> worker.activeCount()==0);
            assertThat(ownedRequests()).allMatch(r -> r.getStatus()==ImageCleanupRequest.Status.COMPLETED);
        }
        assertThat(storage.stored).isEmpty();
    }

    @Test
    void providerFailureAfterDeletionReturnsNotFoundWithoutCreatingAnUpload() throws Exception {
        var dream = ready(0); var source = new CompletableFuture<byte[]>(); generator.pending = source;
        var call = images.generate(user,dream.dreamId(),request(dream));
        expire(generator.last.imageId()); dreams.delete(user,dream.dreamId(),dream.revision());
        source.completeExceptionally(new IllegalStateException("private provider response"));
        error(() -> await(call),ImageErrorCode.NOT_FOUND);
        assertThat(storage.stored).isEmpty(); assertThat(ownedRequests()).isEmpty(); slotsReleased();
    }

    @Test
    void cancellationDoesNotCancelLateDeletionCleanupOrLeakAnExecutionSlot() throws Exception {
        var dream = ready(0); var source = new CompletableFuture<byte[]>(); generator.pending = source;
        var call = images.generate(user,dream.dreamId(),request(dream));
        call.cancel(true); assertThat(source).isNotCancelled();
        expire(generator.last.imageId()); dreams.delete(user,dream.dreamId(),dream.revision());
        storage.failDelete = true; source.complete(ImagePayloadTest.png());
        eventually(() -> ownedRequests().size()==1); slotsReleased();
        assertThat(ownedRequests().getFirst().getReason()).isEqualTo(ImageCleanupRequest.Reason.DISCARDED_UPLOAD);
        assertThat(jdbc.queryForObject("select count(*) from dream_images where user_id=?",Long.class,user)).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void deletionBetweenUploadAndCompletionCannotResurrectImage(boolean failCleanup) throws Exception {
        var dream = ready(0); var uploaded = new CountDownLatch(1); var release = new CountDownLatch(1);
        storage.afterPut = key -> {
            uploaded.countDown();
            try { if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("upload gate timeout"); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
        };
        var call = images.generate(user,dream.dreamId(),request(dream));
        try {
            assertThat(uploaded.await(5,TimeUnit.SECONDS)).isTrue();
            expire(generator.last.imageId()); dreams.delete(user,dream.dreamId(),dream.revision());
            storage.failDelete = failCleanup;
        } finally { release.countDown(); }
        error(() -> await(call),ImageErrorCode.NOT_FOUND); slotsReleased();
        assertThat(jdbc.queryForObject("select count(*) from dream_images where user_id=?",Long.class,user)).isZero();
        if (failCleanup) {
            assertThat(ownedRequests()).hasSize(1); assertThat(storage.stored).hasSize(1);
        } else { assertThat(ownedRequests()).isEmpty(); assertThat(storage.stored).isEmpty(); }
    }

    @Test
    void cleanupStartedWhileUploadWaitsPreventsLateAssetAdoption() throws Exception {
        var dream = ready(0); var uploaded = new CompletableFuture<String>(); var release = new CountDownLatch(1);
        storage.afterPut = key -> {
            uploaded.complete(key);
            try { if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("upload gate timeout"); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
        };
        var call = images.generate(user,dream.dreamId(),request(dream));
        ImageCleanupWorkTransactions.Claim claim;
        try {
            String key = await(uploaded); var input = generator.last;
            expire(input.imageId()); registration.recordDiscardedUpload(user,input.imageId(),key);
            claim = cleanupWork.claim(ownedRequests().getFirst().getId()).orElseThrow();
            // 저장 경로가 lease를 잘못 복구해도 이미 삭제 시작한 키는 채택하지 않는다.
            jdbc.update("update dream_images set lease_until=? where id=?",LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1),input.imageId());
        } finally { release.countDown(); }
        var result = await(call);
        assertThat(result.failureCode()).isEqualTo("ASSET_DISCARDED");
        assertThat(storage.stored).isEmpty();
        cleanupWork.complete(claim); slotsReleased();
        assertThat(ownedRequests().getFirst().getDeletionStartedAt()).isNotNull();
    }

    HttpRequest httpRequest(DreamResponse dream, String access) {
        return HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/dreams/"+dream.dreamId()+"/image"))
                .header("Content-Type","application/json").header("Authorization","Bearer "+access)
                .POST(HttpRequest.BodyPublishers.ofString("{\"revision\":"+dream.revision()+",\"style\":\"test-style\"}")).build();
    }
    @Test
    void httpWaitsAsynchronouslyAndDuplicateGets202ThenOriginalGets200() throws Exception {
        var dream = ready(0); var source = new CompletableFuture<byte[]>(); generator.pending = source;
        String access = tokens.login(user).response().accessToken();
        try (var client = HttpClient.newHttpClient()) {
            var request = httpRequest(dream,access);
            var call = client.sendAsync(request,HttpResponse.BodyHandlers.ofString());
            try {
                eventually(() -> generator.calls.get()==1); assertThat(call).isNotDone();
                var duplicate = client.send(request,HttpResponse.BodyHandlers.ofString());
                assertThat(duplicate.statusCode()).isEqualTo(202);
                assertThat(duplicate.headers().firstValue("Cache-Control")).contains("no-store");
                assertThat(duplicate.body()).contains("PROCESSING").doesNotContain("storageKey");
            } finally { source.complete(ImagePayloadTest.png()); }
            var response = call.get(5,TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("COMPLETED").doesNotContain("storageKey");
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(generator.calls).hasValue(1);
        }
    }

    @Test
    void httpLateDeletedProviderFailureReturnsSanitized404() throws Exception {
        var dream = ready(0); var source = new CompletableFuture<byte[]>(); generator.pending = source;
        try (var client = HttpClient.newHttpClient()) {
            var call = client.sendAsync(httpRequest(dream,tokens.login(user).response().accessToken()),HttpResponse.BodyHandlers.ofString());
            try {
                eventually(() -> generator.calls.get()==1);
                expire(generator.last.imageId()); dreams.delete(user,dream.dreamId(),dream.revision());
            } finally { source.completeExceptionally(new IllegalStateException("https://private.example/?signature=secret")); }
            var response = call.get(5,TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.body()).contains("IMAGE_404_1").doesNotContain("private.example","signature","secret");
            slotsReleased();
        }
    }
}
