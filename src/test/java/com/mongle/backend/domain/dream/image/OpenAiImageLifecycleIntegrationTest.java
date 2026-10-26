package com.mongle.backend.domain.dream.image;

import static com.mongle.backend.domain.dream.gateway.DreamAiTestAwait.await;
import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.dream.analysis.*;
import com.mongle.backend.domain.dream.dto.*;
import com.mongle.backend.domain.dream.entity.DreamEmotion;
import com.mongle.backend.domain.dream.service.DreamService;
import com.mongle.backend.domain.dream.story.*;
import com.mongle.backend.domain.dream.image.openai.*;
import com.mongle.backend.domain.dream.image.s3.*;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.BusinessException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.http.HttpClient;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

@SpringBootTest(properties={
        "spring.datasource.url=jdbc:h2:mem:openai-image-lifecycle;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "mongle.image.styles[0]=test-style","mongle.image.moods[0]=test-mood","mongle.image.execution.max-concurrent-calls=4",
        "mongle.image.openai.enabled=true","mongle.image.openai.api-key=fake-openai-secret",
        "mongle.image.openai.model=gpt-image-1-mini","mongle.image.openai.size=1024x1024","mongle.image.openai.quality=low",
        "mongle.image.openai.style-prompts[test-style]=watercolor","mongle.image.openai.mood-prompts[test-mood]=calm",
        "mongle.image.openai.connect-timeout=100ms","mongle.image.openai.total-timeout=2s",
        "mongle.image.s3.enabled=true","mongle.image.s3.bucket=mongle-test-images","mongle.image.s3.region=ap-northeast-2",
        "mongle.image.s3.connect-timeout=500ms","mongle.image.s3.attempt-timeout=1s","mongle.image.s3.total-timeout=2s",
        "mongle.image.cleanup.enabled=false","mongle.image.cleanup.lease=5s","mongle.image.cleanup.retry-base-delay=1s"})
@ActiveProfiles("test")
@Import(OpenAiImageLifecycleIntegrationTest.Config.class)
class OpenAiImageLifecycleIntegrationTest {
    @TestConfiguration static class Config {
        @Bean @Primary ImageCleanupWorkerIntegrationTest.MutableClock providerClock() {
            var clock=new ImageCleanupWorkerIntegrationTest.MutableClock(); clock.now.set(Instant.now().truncatedTo(ChronoUnit.MICROS)); return clock;
        }
        @Bean OpenAiTestServer providerServer() { return new OpenAiTestServer(); }
        @Bean @Primary OpenAiImageGenerator provider(OpenAiTestServer server,OpenAiImageProperties properties,
                ImageOptions options,ImageExecutionProperties execution,ImageS3Properties storage) {
            return new OpenAiImageGenerator(properties,options,execution,storage,HttpClient.newBuilder()
                    .connectTimeout(properties.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build(),server.endpoint());
        }
        @Bean S3TestTransport transport() { return new S3TestTransport(); }
        @Bean @Primary S3ImageAssetStore providerStorage(S3TestTransport transport,ImageCleanupWorkerIntegrationTest.MutableClock clock) {
            return new S3ImageAssetStore(transport.client(1),S3TestTransport.presigner(),"mongle-test-images",clock);
        }
        @Bean @Primary StructureGenerator providerStructure() { return input->CompletableFuture.completedFuture(DreamImageIntegrationTest.STRUCTURE); }
        @Bean @Primary StoryGenerator providerStory() { return input->CompletableFuture.completedFuture(DreamImageIntegrationTest.STORY); }
    }
    @Autowired OpenAiTestServer server;
    @Autowired S3TestTransport transport;
    @Autowired S3ImageAssetStore storage;
    @Autowired ImageCleanupWorkerIntegrationTest.MutableClock clock;
    @Autowired ImageCleanupWorkTransactions work;
    @Autowired ImageCleanupRequestRepository cleanupRequests;
    @Autowired ImageCleanupProperties cleanup;
    @Autowired DreamService dreams;
    @Autowired DreamStructureService analyses;
    @Autowired DreamStoryService stories;
    @Autowired DreamImageService images;
    @Autowired ImageTransactions transactions;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    Long user;
    CountDownLatch gate;

    @BeforeEach void setup() {
        cleanupRequests.deleteAll(); server.reset(); transport.reset(); gate=new CountDownLatch(1);
        clock.now.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        user=users.saveAndFlush(User.create(UUID.randomUUID()+"@test.com","Provider검증")).getId();
    }
    @AfterEach void release() { gate.countDown(); }
    DreamResponse ready() {
        var draft=dreams.create(user,new DreamCreateRequest(LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))),"하늘의 바다를 날았다"));
        var dream=dreams.complete(user,draft.dreamId(),new DreamEmotionsRequest(draft.revision(),List.of(DreamEmotion.HAPPY)));
        await(analyses.analyze(user,dream.dreamId(),dream.revision())); dream=dreams.get(user,dream.dreamId());
        await(stories.generate(user,dream.dreamId(),new StoryRequest(dream.revision(),false,null)));
        jdbc.update("update dream_generation_jobs set status='COMPLETED' where dream_id=?",dream.dreamId());
        return dreams.get(user,dream.dreamId());
    }
    ImageRequest request(DreamResponse dream) { return new ImageRequest(dream.revision(),"test-style","test-mood",false,null); }
    void success() { server.replies.add(new OpenAiTestServer.Reply(200,OpenAiTestServer.body(OpenAiTestServer.image("png")))); }
    void eventually(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(!condition.getAsBoolean() && System.nanoTime()<end) Thread.sleep(10);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    @Test void singleRequestStoresS3ResultReusesCacheAndExplicitRegenerationCallsAgain() {
        var dream=ready(); success(); var first=await(images.generate(user,dream.dreamId(),request(dream)));
        assertThat(first.status()).isEqualTo(GenerationStatus.COMPLETED);
        String old=transactions.assetKey(user,first.imageId());
        assertThat(transport.objects.get(old)).containsExactly(OpenAiTestServer.image("png"));
        assertThat(await(images.generate(user,dream.dreamId(),request(dream))).imageVersion()).isEqualTo(first.imageVersion());
        assertThat(server.received).hasSize(1);
        success(); var next=await(images.generate(user,dream.dreamId(),new ImageRequest(dream.revision(),"test-style","test-mood",true,first.imageVersion())));
        assertThat(next.imageVersion()).isGreaterThan(first.imageVersion()); assertThat(server.received).hasSize(2);
        assertThat(transactions.assetKey(user,first.imageId())).isNotEqualTo(old);
        assertThat(transport.objects).hasSize(2).containsKey(old);
        assertThat(transport.calls).allMatch(call->!call.transaction() && call.thread().startsWith("image-result-"));
    }

    @ParameterizedTest @ValueSource(ints={401,429,500})
    void providerFailurePreservesPreviousSuccessfulFileWithoutRetry(int status) {
        var dream=ready(); success(); var first=await(images.generate(user,dream.dreamId(),request(dream)));
        String old=transactions.assetKey(user,first.imageId()); server.replies.add(new OpenAiTestServer.Reply(status,"private-provider-error"));
        var failed=await(images.generate(user,dream.dreamId(),new ImageRequest(dream.revision(),"test-style","test-mood",true,first.imageVersion())));
        assertThat(failed.status()).isEqualTo(GenerationStatus.FAILED); assertThat(failed.hasPreviousResult()).isTrue();
        assertThat(failed.failureCode()).isEqualTo(status==401 ? "PROVIDER_AUTH_FAILED" : status==429 ? "PROVIDER_RATE_LIMITED" : "PROVIDER_UNAVAILABLE");
        assertThat(transactions.assetKey(user,first.imageId())).isEqualTo(old);
        assertThat(transport.objects).containsOnlyKeys(old); assertThat(server.received).hasSize(2);
    }

    @Test void pendingReuseGuardAndLateDeletedUploadReachDurableCleanupWorker() throws Exception {
        var dream=ready(); server.replies.add(new OpenAiTestServer.Reply(200,OpenAiTestServer.body(OpenAiTestServer.image("png")),Map.of(),null,gate));
        var pending=images.generate(user,dream.dreamId(),request(dream)); eventually(()->server.received.size()==1);
        assertThat(pending).isNotDone();
        assertThat(await(images.generate(user,dream.dreamId(),request(dream))).status()).isEqualTo(GenerationStatus.PROCESSING);
        assertThatThrownBy(()->dreams.delete(user,dream.dreamId(),dream.revision())).isInstanceOf(BusinessException.class);
        assertThat(server.received).hasSize(1); clock.advance(Duration.ofMinutes(3));
        dreams.delete(user,dream.dreamId(),dream.revision());
        transport.afterPut=call->transport.failures.add(new S3TestTransport.Failure(403,"AccessDenied")); gate.countDown();
        assertThatThrownBy(()->await(pending)).isInstanceOfSatisfying(BusinessException.class,
                ex->assertThat(ex.getErrorCode()).isEqualTo(ImageErrorCode.NOT_FOUND));
        assertThat(transport.objects).hasSize(1);
        var request=cleanupRequests.findAll().getFirst();
        assertThat(request.getReason()).isEqualTo(ImageCleanupRequest.Reason.DISCARDED_UPLOAD);
        transport.afterPut=ignored->{};
        try(var worker=new ImageCleanupWorker(work,storage,new ImageCleanupProperties(true,cleanup.pollInterval(),cleanup.batchSize(),
                cleanup.maxConcurrentCalls(),cleanup.lease(),cleanup.retryBaseDelay(),cleanup.retryMaxDelay(),cleanup.maxAttempts()))) {
            worker.poll(); eventually(()->worker.activeCount()==0);
        }
        assertThat(transport.objects).isEmpty();
        assertThat(cleanupRequests.findById(request.getId()).orElseThrow().getStatus()).isEqualTo(ImageCleanupRequest.Status.COMPLETED);
        assertThat(jdbc.queryForObject("select count(*) from dream_images where id=?",Long.class,request.getImageId())).isZero();
    }

    @Test void cancelledHttpFacingFutureStillPublishesResultAndReleasesGenerationSlot() throws Exception {
        var dream=ready(); server.replies.add(new OpenAiTestServer.Reply(200,OpenAiTestServer.body(OpenAiTestServer.image("png")),Map.of(),null,gate));
        var pending=images.generate(user,dream.dreamId(),request(dream)); eventually(()->server.received.size()==1);
        assertThat(pending.cancel(true)).isTrue(); gate.countDown();
        eventually(()->transactions.latest(user,dream.dreamId()).status()==GenerationStatus.COMPLETED);
        assertThat(server.received).hasSize(1); assertThat(transport.objects).hasSize(1);
        var first=transactions.latest(user,dream.dreamId()); success();
        assertThat(await(images.generate(user,dream.dreamId(),new ImageRequest(dream.revision(),"test-style","test-mood",true,first.imageVersion()))).status())
                .isEqualTo(GenerationStatus.COMPLETED);
        assertThat(server.received).hasSize(2);
    }

    @ParameterizedTest @ValueSource(strings={"bad-base64","PHN2Zz5ub3QtaW1hZ2U8L3N2Zz4="})
    void invalidProviderOutputNeverUploadsToS3(String encoded) {
        var dream=ready(); server.replies.add(new OpenAiTestServer.Reply(200,"{\"data\":[{\"b64_json\":\""+encoded+"\"}]}"));
        var result=await(images.generate(user,dream.dreamId(),request(dream)));
        assertThat(result.status()).isEqualTo(GenerationStatus.FAILED); assertThat(result.failureCode()).isEqualTo("INVALID_OUTPUT");
        assertThat(transport.calls).isEmpty(); assertThat(transport.objects).isEmpty();
    }
}
