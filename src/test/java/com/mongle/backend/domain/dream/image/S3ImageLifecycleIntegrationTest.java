package com.mongle.backend.domain.dream.image;

import static com.mongle.backend.domain.dream.gateway.DreamAiTestAwait.await;
import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.dream.analysis.*;
import com.mongle.backend.domain.dream.dto.*;
import com.mongle.backend.domain.dream.entity.DreamEmotion;
import com.mongle.backend.domain.dream.service.DreamService;
import com.mongle.backend.domain.dream.story.*;
import com.mongle.backend.domain.dream.image.s3.*;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.BusinessException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.system.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.http.SdkHttpMethod;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

@SpringBootTest(properties = {
        "mongle.credit.enabled=true", "mongle.credit.dream-image-generation=2", "mongle.credit.dream-image-regeneration=3",
        "spring.datasource.url=jdbc:h2:mem:s3-image-lifecycle;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "mongle.image.styles[0]=test-style", "mongle.image.cleanup.enabled=false",
        "mongle.image.cleanup.lease=5s", "mongle.image.cleanup.retry-base-delay=1s",
        "mongle.image.s3.enabled=true", "mongle.image.s3.bucket=mongle-test-images",
        "mongle.image.s3.region=ap-northeast-2", "mongle.image.s3.connect-timeout=500ms",
        "mongle.image.s3.attempt-timeout=1s", "mongle.image.s3.total-timeout=2s"})
@ActiveProfiles("test")
@Import(S3ImageLifecycleIntegrationTest.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class S3ImageLifecycleIntegrationTest {
    static class Generator implements ImageGenerator {
        volatile CompletableFuture<byte[]> next;
        @Override public CompletableFuture<byte[]> generate(Input input) {
            return next == null ? CompletableFuture.completedFuture(ImagePayloadTest.png()) : next;
        }
    }
    @TestConfiguration
    static class Config {
        @Bean @Primary ImageCleanupWorkerIntegrationTest.MutableClock s3Clock() {
            var clock = new ImageCleanupWorkerIntegrationTest.MutableClock();
            clock.now.set(Instant.now().truncatedTo(ChronoUnit.MICROS)); return clock;
        }
        @Bean S3TestTransport transport() { return new S3TestTransport(); }
        @Bean @Primary S3ImageAssetStore s3Store(S3TestTransport transport,
                ImageCleanupWorkerIntegrationTest.MutableClock clock) {
            return new S3ImageAssetStore(transport.client(1),S3TestTransport.presigner(),"mongle-test-images",clock);
        }
        @Bean @Primary Generator generator() { return new Generator(); }
        @Bean @Primary StructureGenerator structureGenerator() {
            return input -> CompletableFuture.completedFuture(DreamImageIntegrationTest.STRUCTURE);
        }
        @Bean @Primary StoryGenerator storyGenerator() {
            return input -> CompletableFuture.completedFuture(DreamImageIntegrationTest.STORY);
        }
    }
    @Autowired com.mongle.backend.domain.credit.CreditService credits;
    @Autowired S3TestTransport transport;
    @Autowired Generator generator;
    @Autowired ImageCleanupWorkerIntegrationTest.MutableClock clock;
    @Autowired S3ImageAssetStore storage;
    @Autowired ImageCleanupWorkTransactions work;
    @Autowired ImageCleanupTransactions registration;
    @Autowired ImageCleanupRequestRepository requests;
    @Autowired ImageCleanupProperties properties;
    @Autowired DreamService dreams;
    @Autowired DreamStructureService analyses;
    @Autowired DreamStoryService stories;
    @Autowired DreamImageService images;
    @Autowired ImageTransactions transactions;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    ImageCleanupWorker worker;
    Long user;

    @BeforeEach void setup() {
        requests.deleteAll(); transport.reset(); generator.next = null;
        clock.now.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        user = users.saveAndFlush(User.create(UUID.randomUUID()+"@test.com","S3검증")).getId();
        credits.grant(user, com.mongle.backend.domain.credit.CreditSource.SIGNUP, "image-test:"+user, 1000);
        worker = new ImageCleanupWorker(work,storage,new ImageCleanupProperties(true,properties.pollInterval(),
                properties.batchSize(),properties.maxConcurrentCalls(),properties.lease(),properties.retryBaseDelay(),
                properties.retryMaxDelay(),properties.maxAttempts()));
    }
    @AfterEach void close() {
        if (generator.next != null) generator.next.complete(ImagePayloadTest.png());
        worker.close();
    }
    DreamResponse ready() {
        var draft = dreams.create(user,new DreamCreateRequest(LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))),"바다에서 날았다"));
        var dream = dreams.complete(user,draft.dreamId(),new DreamEmotionsRequest(draft.revision(),List.of(DreamEmotion.HAPPY)));
        await(analyses.analyze(user,dream.dreamId(),dream.revision()));
        dream = dreams.get(user,dream.dreamId());
        await(stories.generate(user,dream.dreamId(),new StoryRequest(dream.revision(),false,null)));
        jdbc.update("update dream_generation_jobs set status='COMPLETED' where dream_id=?",dream.dreamId());
        return dreams.get(user,dream.dreamId());
    }
    ImageRequest request(DreamResponse dream) { return new ImageRequest(dream.revision(),"test-style",null,false,null); }
    void runWorker() throws Exception {
        worker.poll();
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(worker.activeCount()!=0 && System.nanoTime()<end) Thread.sleep(10);
        assertThat(worker.activeCount()).isZero();
    }

    @Test void successfulUploadIsReusedAndDeletedOnlyByWorkerAfterDreamDeletion() throws Exception {
        var dream=ready(); var image=await(images.generate(user,dream.dreamId(),request(dream)));
        String key=transactions.assetKey(user,image.imageId());
        assertThat(transport.objects.get(key)).containsExactly(ImagePayloadTest.png());
        assertThat(transport.calls).hasSize(1);
        assertThat(transport.calls.getFirst().transaction()).isFalse();
        assertThat(transport.calls.getFirst().thread()).startsWith("image-result-");
        assertThat(await(images.generate(user,dream.dreamId(),request(dream))).imageId()).isEqualTo(image.imageId());
        assertThat(transport.calls).hasSize(1);
        dreams.delete(user,dream.dreamId(),dream.revision());
        assertThat(transport.objects).containsKey(key);
        assertThat(requests.findAll().getFirst().getStatus()).isEqualTo(ImageCleanupRequest.Status.PENDING);
        runWorker();
        assertThat(transport.objects).doesNotContainKey(key);
        assertThat(requests.findAll().getFirst().getStatus()).isEqualTo(ImageCleanupRequest.Status.COMPLETED);
        var deletion=transport.calls.getLast();
        assertThat(deletion.transaction()).isFalse(); assertThat(deletion.thread()).startsWith("image-cleanup-io-");
        assertThatThrownBy(() -> transactions.get(user,image.imageId())).isInstanceOf(BusinessException.class);
    }

    @Test void failedRegenerationPreservesPreviousS3ObjectAndReportsFailure() {
        var dream=ready(); var previous=await(images.generate(user,dream.dreamId(),request(dream)));
        String key=transactions.assetKey(user,previous.imageId());
        transport.failures.add(new S3TestTransport.Failure(403,"AccessDenied"));
        var result=await(images.generate(user,dream.dreamId(),new ImageRequest(dream.revision(),"test-style",null,true,previous.imageVersion())));
        assertThat(result.status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(result.hasPreviousResult()).isTrue(); assertThat(result.failureCode()).isEqualTo("STORAGE_FAILED");
        assertThat(transactions.assetKey(user,previous.imageId())).isEqualTo(key);
        assertThat(transport.objects).containsOnlyKeys(key);
        assertThat(transport.calls.getLast().method()).isEqualTo(SdkHttpMethod.DELETE);
        assertThat(transport.calls.getLast().key()).isNotEqualTo(key);
    }

    @Test void accessFailureKeepsCleanupRequestForRetryAndNeverLogsAwsSecrets(CapturedOutput output) throws Exception {
        var dream=ready(); var image=await(images.generate(user,dream.dreamId(),request(dream)));
        String key=transactions.assetKey(user,image.imageId());
        dreams.delete(user,dream.dreamId(),dream.revision());
        transport.failures.add(new S3TestTransport.Failure(403,"AccessDenied")); runWorker();
        assertThat(transport.objects).containsKey(key);
        assertThat(requests.findAll().getFirst().getStatus()).isEqualTo(ImageCleanupRequest.Status.RETRY_WAIT);
        clock.advance(Duration.ofSeconds(1)); runWorker();
        assertThat(requests.findAll().getFirst().getStatus()).isEqualTo(ImageCleanupRequest.Status.COMPLETED);
        assertThat(transport.objects).doesNotContainKey(key);
        assertThat(output.getAll()).doesNotContain(key,"private-aws-body","test-secret","X-Amz-Signature");
    }

    @Test void lateUploadAfterDeletionAndFailedCompensationIsRecoveredByS3CleanupWorker() throws Exception {
        var dream=ready(); generator.next=new CompletableFuture<>();
        var pending=images.generate(user,dream.dreamId(),request(dream));
        assertThat(pending).isNotDone(); clock.advance(Duration.ofMinutes(3));
        dreams.delete(user,dream.dreamId(),dream.revision());
        transport.afterPut=call -> transport.failures.add(new S3TestTransport.Failure(403,"AccessDenied"));
        generator.next.complete(ImagePayloadTest.png());
        assertThatThrownBy(() -> await(pending)).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ImageErrorCode.NOT_FOUND));
        assertThat(transport.objects).hasSize(1);
        var request=requests.findAll().getFirst();
        assertThat(request.getReason()).isEqualTo(ImageCleanupRequest.Reason.DISCARDED_UPLOAD);
        transport.afterPut=ignored -> {}; runWorker();
        assertThat(transport.objects).isEmpty();
        assertThat(requests.findById(request.getId()).orElseThrow().getStatus()).isEqualTo(ImageCleanupRequest.Status.COMPLETED);
        assertThat(jdbc.queryForObject("select count(*) from dream_images where id=?",Long.class,request.getImageId())).isZero();
    }

    @Test void cleanupWorkerProtectsReferencedS3ObjectUntilDreamDeletion() throws Exception {
        var dream=ready(); var image=await(images.generate(user,dream.dreamId(),request(dream)));
        String key=transactions.assetKey(user,image.imageId());
        registration.recordDiscardedUpload(user,image.imageId(),key); runWorker();
        assertThat(requests.findAll().getFirst().getFailureCode()).isEqualTo("REFERENCED");
        assertThat(requests.findAll().getFirst().getAttemptCount()).isZero();
        assertThat(transport.calls).hasSize(1); assertThat(transport.objects).containsKey(key);
        dreams.delete(user,dream.dreamId(),dream.revision()); clock.advance(Duration.ofSeconds(1)); runWorker();
        assertThat(transport.objects).doesNotContainKey(key);
        assertThat(requests.findAll().getFirst().getStatus()).isEqualTo(ImageCleanupRequest.Status.COMPLETED);
    }
}
