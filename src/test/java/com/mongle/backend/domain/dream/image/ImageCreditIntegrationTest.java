package com.mongle.backend.domain.dream.image;

import static com.mongle.backend.domain.dream.gateway.DreamAiTestAwait.await;
import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.credit.*;
import com.mongle.backend.domain.dream.analysis.DreamStructureService;
import com.mongle.backend.domain.dream.dto.*;
import com.mongle.backend.domain.dream.entity.DreamEmotion;
import com.mongle.backend.domain.dream.service.DreamService;
import com.mongle.backend.domain.dream.story.*;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:image-credits;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "mongle.credit.enabled=true", "mongle.credit.dream-image-generation=2",
    "mongle.credit.dream-image-regeneration=3", "mongle.image.styles[0]=test-style"})
@ActiveProfiles("test")
@Import(DreamImageIntegrationTest.Config.class)
class ImageCreditIntegrationTest {
    @Autowired UserRepository users;
    @Autowired com.mongle.backend.domain.auth.service.TokenService tokens;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    @Autowired DreamService dreams;
    @Autowired DreamStructureService analyses;
    @Autowired DreamStoryService stories;
    @Autowired DreamImageService images;
    @Autowired ImageTransactions transactions;
    @Autowired ImageRecovery recovery;
    @Autowired CreditService credits;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean CreditProperties creditProperties;
    @Autowired CreditReservationService reservations;
    @Autowired DreamImageIntegrationTest.FakeGenerator generator;
    @Autowired DreamImageIntegrationTest.FakeStore storage;
    @Autowired JdbcTemplate jdbc;
    Long user;

    @BeforeEach void setup() {
        user=users.saveAndFlush(User.create(UUID.randomUUID()+"@test.com","과금검증")).getId();
        generator.enabled=true; generator.calls.set(0); generator.pending=null;
        generator.action=input -> ImagePayloadTest.png();
        storage.enabled=true; storage.failPut=false; storage.failDelete=false;
        storage.afterPut=key -> {}; storage.stored.clear(); storage.deleted.clear();
    }
    @AfterEach void closePending() { if(generator.pending!=null) generator.pending.complete(ImagePayloadTest.png()); }
    DreamResponse ready() {
        var draft=dreams.create(user,new DreamCreateRequest(LocalDate.now(ZoneId.of("Asia/Seoul")),"바다를 보았다"));
        var dream=dreams.complete(user,draft.dreamId(),new DreamEmotionsRequest(draft.revision(),List.of(DreamEmotion.HAPPY)));
        await(analyses.analyze(user,dream.dreamId(),dream.revision()));
        dream=dreams.get(user,dream.dreamId());
        await(stories.generate(user,dream.dreamId(),new StoryRequest(dream.revision(),false,null)));
        return dream;
    }
    void fund(long amount) { credits.grant(user,CreditSource.PAYMENT,"payment-test:"+user,amount); }
    ImageRequest request(DreamResponse dream) { return new ImageRequest(dream.revision(),"test-style",null,false,null); }
    ImageRequest regenerate(DreamResponse dream,ImageResponse image) { return new ImageRequest(dream.revision(),"test-style",null,true,image.imageVersion()); }
    long count(String kind) { return jdbc.queryForObject("select count(*) from credit_ledger where user_id=? and kind=?",Long.class,user,kind); }
    Long reservation(Long image) { return jdbc.queryForObject("select credit_reservation_id from dream_images where id=?",Long.class,image); }

    @Test void missingRegenerationPriceRejectsFreeAttemptWithoutChangingImageOrCredits() {
        fund(2);var dream=ready();var first=await(images.generate(user,dream.dreamId(),request(dream)));
        var storedKeys=Set.copyOf(storage.stored.keySet());
        var persisted=jdbc.queryForMap("select * from dream_images where id=?",first.imageId());
        org.mockito.Mockito.doReturn(null).when(creditProperties).configuredCost(CreditUsage.DREAM_IMAGE_REGENERATION);

        assertThatThrownBy(() -> images.generate(user,dream.dreamId(),regenerate(dream,first)))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(CreditErrorCode.UNAVAILABLE);
        var unchanged=transactions.latest(user,dream.dreamId());
        assertThat(unchanged.freeRegenerationAvailable()).isTrue();assertThat(unchanged.nextRegenerationCost()).isNull();
        assertThat(jdbc.queryForMap("select * from dream_images where id=?",first.imageId())).isEqualTo(persisted);
        assertThat(credits.balance(user).available()).isZero();assertThat(credits.balance(user).reserved()).isZero();
        assertThat(count("RESERVE")).isEqualTo(1);assertThat(count("SETTLE")).isEqualTo(1);assertThat(count("RELEASE")).isZero();
        assertThat(generator.calls).hasValue(1);assertThat(storage.stored.keySet()).isEqualTo(storedKeys);
        assertThat(await(images.generate(user,dream.dreamId(),request(dream)))).isEqualTo(unchanged);

        org.mockito.Mockito.doCallRealMethod().when(creditProperties).configuredCost(CreditUsage.DREAM_IMAGE_REGENERATION);
        var free=await(images.generate(user,dream.dreamId(),regenerate(dream,unchanged)));
        assertThat(free.freeRegenerationAvailable()).isFalse();assertThat(credits.balance(user).available()).isZero();
        assertThat(generator.calls).hasValue(2);assertThat(count("RESERVE")).isEqualTo(1);
    }

    @Test void freeRegenerationWorksAtZeroBalanceAndConcurrentDuplicatesCallProviderOnce() throws Exception {
        fund(2); var dream=ready(); var first=await(images.generate(user,dream.dreamId(),request(dream)));
        assertThat(credits.balance(user).available()).isZero();
        generator.pending=new CompletableFuture<>();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var request=regenerate(dream,first);
            var one=pool.submit(() -> images.generate(user,dream.dreamId(),request));
            var two=pool.submit(() -> images.generate(user,dream.dreamId(),request));
            var call1=one.get(5,TimeUnit.SECONDS); var call2=two.get(5,TimeUnit.SECONDS);
            assertThat(generator.calls).hasValue(2); assertThat(count("RESERVE")).isEqualTo(1);
            generator.pending.complete(ImagePayloadTest.png());
            await(call1); await(call2);
        }
        var done=transactions.latest(user,dream.dreamId());
        transactions.finish(generator.last,storage.stored.keySet().iterator().next(),ImagePayload.parse(ImagePayloadTest.png()));
        assertThat(done.freeRegenerationAvailable()).isFalse();assertThat(done.nextRegenerationCost()).isEqualTo(3);
        assertThat(count("SETTLE")).isEqualTo(1);assertThat(credits.balance(user).available()).isZero();
        assertThatThrownBy(() -> images.generate(user,dream.dreamId(),regenerate(dream,done)))
            .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(CreditErrorCode.INSUFFICIENT);
        assertThat(generator.calls).hasValue(2);
    }

    @Test void failedAndExpiredFreeAttemptsDoNotConsumeAllowanceIncludingOrdinaryRetry() {
        fund(2);var dream=ready();var first=await(images.generate(user,dream.dreamId(),request(dream)));
        generator.action=input -> new byte[]{1};
        var failed=await(images.generate(user,dream.dreamId(),regenerate(dream,first)));
        assertThat(failed.freeRegenerationAvailable()).isTrue();assertThat(failed.hasPreviousResult()).isTrue();
        generator.action=input -> ImagePayloadTest.png();generator.pending=new CompletableFuture<>();
        var expired=images.generate(user,dream.dreamId(),request(dream));
        expireImage(generator.last.imageId());recovery.recoverExpired();
        var oldInput=generator.last;
        generator.pending.complete(ImagePayloadTest.png());assertThat(await(expired).failureCode()).isEqualTo("ATTEMPT_EXPIRED");
        assertThat(transactions.latest(user,dream.dreamId()).freeRegenerationAvailable()).isTrue();
        generator.pending=null;
        var done=await(images.generate(user,dream.dreamId(),request(dream)));
        assertThat(done.freeRegenerationAvailable()).isFalse();assertThat(count("SETTLE")).isEqualTo(1);
        assertThat(transactions.finish(oldInput,"dream-images/discarded",ImagePayload.parse(ImagePayloadTest.png())).stored()).isFalse();
        assertThat(credits.balance(user).available()).isZero();assertThat(count("RESERVE")).isEqualTo(1);
    }

    @Test void freeAllowanceRollsBackWhenSuccessfulImageCannotBePersisted() {
        fund(2);var dream=ready();var first=await(images.generate(user,dream.dreamId(),request(dream)));
        String previousKey=transactions.assetKey(user,first.imageId());
        generator.pending=new CompletableFuture<>();var call=images.generate(user,dream.dreamId(),regenerate(dream,first));
        jdbc.execute("alter table dream_images add constraint free_image_publish_failure check(id<>"+first.imageId()+" or status<>'COMPLETED')");
        try {
            generator.pending.complete(ImagePayloadTest.png());assertThatThrownBy(() -> await(call)).isInstanceOf(BusinessException.class);
            var failed=transactions.latest(user,dream.dreamId());
            assertThat(failed.freeRegenerationAvailable()).isTrue();assertThat(failed.hasPreviousResult()).isTrue();
            assertThat(count("SETTLE")).isEqualTo(1);assertThat(storage.stored).hasSize(1);
            assertThat(transactions.assetKey(user,first.imageId())).isEqualTo(previousKey);
            assertThat(jdbc.queryForObject("select count(*) from image_cleanup_requests where storage_key=?",Long.class,previousKey)).isZero();
        } finally {jdbc.execute("alter table dream_images drop constraint free_image_publish_failure");}
        generator.pending=null;
        assertThat(await(images.generate(user,dream.dreamId(),request(dream))).freeRegenerationAvailable()).isFalse();
    }

    @Test void regenerationFlagAfterFailedInitialGenerationStillChargesInitialPrice() {
        fund(10);var dream=ready();generator.action=input -> new byte[]{1};
        var failed=await(images.generate(user,dream.dreamId(),request(dream)));
        assertThat(failed.freeRegenerationAvailable()).isFalse();assertThat(failed.nextRegenerationCost()).isNull();
        generator.action=input -> ImagePayloadTest.png();
        var first=await(images.generate(user,dream.dreamId(),regenerate(dream,failed)));
        assertThat(first.freeRegenerationAvailable()).isTrue();assertThat(credits.balance(user).available()).isEqualTo(8);
        assertThat(jdbc.queryForObject("select amount from credit_reservations where id=?",Long.class,reservation(first.imageId()))).isEqualTo(2);
    }

    @Test void sourceAndEmotionEditsDoNotResetUsedAllowanceOrCallImageProvider() {
        fund(10);var dream=ready();var first=await(images.generate(user,dream.dreamId(),request(dream)));
        var free=await(images.generate(user,dream.dreamId(),regenerate(dream,first)));
        var edit=new DreamUpdateRequest();edit.setRevision(dream.revision());edit.setOriginalText("바다를 오래 보았다");
        dreams.update(user,dream.dreamId(),edit);
        var current=dreams.get(user,dream.dreamId());
        var emotions=new DreamUpdateRequest();emotions.setRevision(current.revision());emotions.setEmotions(List.of(DreamEmotion.HAPPY,DreamEmotion.SAD));
        dreams.update(user,current.dreamId(),emotions);
        var stale=transactions.latest(user,dream.dreamId());
        assertThat(stale.imageId()).isEqualTo(free.imageId());assertThat(stale.sourceChanged()).isTrue();
        assertThat(stale.freeRegenerationAvailable()).isFalse();assertThat(stale.nextRegenerationCost()).isEqualTo(3);
        assertThat(generator.calls).hasValue(2);
    }

    @Test void successReuseAndExplicitRegenerationHaveDifferentCharges() {
        fund(10); var dream=ready();
        var first=await(images.generate(user,dream.dreamId(),request(dream)));
        assertThat(credits.balance(user).available()).isEqualTo(8);
        assertThat(credits.balance(user).reserved()).isZero();
        assertThat(await(images.generate(user,dream.dreamId(),request(dream)))).isEqualTo(first);
        assertThat(count("RESERVE")).isEqualTo(1); assertThat(count("SETTLE")).isEqualTo(1);
        var second=await(images.generate(user,dream.dreamId(),regenerate(dream,first)));
        assertThat(second.status()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(first.freeRegenerationAvailable()).isTrue(); assertThat(first.nextRegenerationCost()).isZero();
        assertThat(second.freeRegenerationAvailable()).isFalse(); assertThat(second.nextRegenerationCost()).isEqualTo(3);
        assertThat(credits.balance(user).available()).isEqualTo(8);
        assertThat(reservation(second.imageId())).isNull();
        var third=await(images.generate(user,dream.dreamId(),regenerate(dream,second)));
        assertThat(third.status()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(credits.balance(user).available()).isEqualTo(5);
        assertThat(generator.calls).hasValue(3); assertThat(count("SETTLE")).isEqualTo(2);
    }
    @Test void unavailableCostBlocksNewGenerationButDoesNotBlockFreeReuse() {
        fund(10); var dream=ready(); var first=await(images.generate(user,dream.dreamId(),request(dream)));
        first=await(images.generate(user,dream.dreamId(),regenerate(dream,first)));
        final var paid=first;
        org.mockito.Mockito.doThrow(new BusinessException(CreditErrorCode.UNAVAILABLE))
            .when(creditProperties).cost(CreditUsage.DREAM_IMAGE_REGENERATION);
        assertThat(await(images.generate(user,dream.dreamId(),request(dream)))).isEqualTo(first);
        assertThatThrownBy(() -> images.generate(user,dream.dreamId(),regenerate(dream,paid)))
            .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(CreditErrorCode.UNAVAILABLE);
        assertThat(count("RESERVE")).isEqualTo(1); assertThat(generator.calls).hasValue(2);
        assertThat(transactions.latest(user,dream.dreamId())).isEqualTo(first);
    }
    @Test void reservationSaveFailureRollsBackImageStartAndBalance() {
        fund(10); var dream=ready();
        jdbc.execute("alter table credit_reservations add constraint credit_image_reserve_failure check(user_id<>"+user+")");
        try {
            assertThatThrownBy(() -> images.generate(user,dream.dreamId(),request(dream))).isInstanceOf(RuntimeException.class);
            assertThat(count("RESERVE")).isZero(); assertThat(generator.calls).hasValue(0);
            assertThat(credits.balance(user).available()).isEqualTo(10); assertThat(credits.balance(user).reserved()).isZero();
            assertThat(jdbc.queryForObject("select count(*) from dream_images where user_id=?",Long.class,user)).isZero();
        } finally { jdbc.execute("alter table credit_reservations drop constraint credit_image_reserve_failure"); }
    }
    @Test void insufficientBalanceRollsBackImageAndNeverCallsProvider() {
        fund(1); var dream=ready();
        assertThatThrownBy(() -> images.generate(user,dream.dreamId(),request(dream)))
            .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(CreditErrorCode.INSUFFICIENT);
        assertThat(generator.calls).hasValue(0); assertThat(count("RESERVE")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from dream_images where user_id=?",Long.class,user)).isZero();
        assertThat(credits.balance(user).available()).isEqualTo(1);
    }
    @Test void pendingDuplicateReservesOnceAndLateRepeatCannotSettleTwice() {
        fund(10); var dream=ready(); generator.pending=new CompletableFuture<>();
        var call=images.generate(user,dream.dreamId(),request(dream));
        var pending=await(images.generate(user,dream.dreamId(),request(dream)));
        assertThat(credits.balance(user).available()).isEqualTo(8); assertThat(credits.balance(user).reserved()).isEqualTo(2);
        assertThat(count("RESERVE")).isEqualTo(1); assertThat(generator.calls).hasValue(1);
        generator.pending.complete(ImagePayloadTest.png()); var done=await(call);
        transactions.finish(generator.last,storage.stored.keySet().iterator().next(),ImagePayload.parse(ImagePayloadTest.png()));
        assertThat(count("SETTLE")).isEqualTo(1); assertThat(credits.balance(user).reserved()).isZero();
        assertThat(done.imageId()).isEqualTo(pending.imageId());
    }
    @Test void invalidOutputAndStorageFailureRestoreAllReservedCredits() {
        fund(10); var dream=ready(); generator.action=input -> new byte[]{1};
        assertThat(await(images.generate(user,dream.dreamId(),request(dream))).failureCode()).isEqualTo("INVALID_OUTPUT");
        assertThat(credits.balance(user).available()).isEqualTo(10);
        generator.action=input -> ImagePayloadTest.png(); storage.failPut=true;
        assertThat(await(images.generate(user,dream.dreamId(),request(dream))).failureCode()).isEqualTo("STORAGE_FAILED");
        assertThat(credits.balance(user).available()).isEqualTo(10); assertThat(credits.balance(user).reserved()).isZero();
        assertThat(count("RELEASE")).isEqualTo(2); assertThat(count("SETTLE")).isZero();
    }
    @Test void releasedReservationCannotPublishImageEvenWithActiveAttempt() {
        fund(10); var dream=ready(); generator.pending=new CompletableFuture<>();
        var call=images.generate(user,dream.dreamId(),request(dream));
        var running=transactions.latest(user,dream.dreamId());
        reservations.release(user,reservation(running.imageId()),CreditReservation.ReleaseReason.CANCELLED);
        generator.pending.complete(ImagePayloadTest.png());
        assertThat(await(call).failureCode()).isEqualTo("CREDIT_RESERVATION_EXPIRED");
        assertThat(count("SETTLE")).isZero(); assertThat(credits.balance(user).available()).isEqualTo(10);
        assertThat(storage.stored).isEmpty();
    }
    @Test void cancellationDuringRegenerationPreservesPreviousImageAndReleasesReservation() {
        fund(10); var dream=ready(); var first=await(images.generate(user,dream.dreamId(),request(dream)));
        generator.pending=new CompletableFuture<>(); var call=images.generate(user,dream.dreamId(),regenerate(dream,first));
        credits.holdPayment(user,"payment-test:"+user); generator.pending.complete(ImagePayloadTest.png());
        var failed=await(call); assertThat(failed.failureCode()).isEqualTo("CREDIT_PAYMENT_REVIEW");
        assertThat(failed.hasPreviousResult()).isTrue(); assertThat(storage.stored).hasSize(1);
        assertThat(count("SETTLE")).isEqualTo(1); assertThat(credits.balance(user).available()).isEqualTo(8);
        assertThat(credits.balance(user).reserved()).isZero();
        assertThatThrownBy(() -> images.generate(user,dream.dreamId(),regenerate(dream,failed)))
            .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(CreditErrorCode.PAYMENT_REVIEW);
    }
    @Test void paymentHoldDoesNotBlockCompletedResultReuseOrReads() {
        fund(10); var dream=ready(); var first=await(images.generate(user,dream.dreamId(),request(dream)));
        credits.holdPayment(user,"payment-test:"+user);
        assertThat(await(images.generate(user,dream.dreamId(),request(dream)))).isEqualTo(first);
        assertThat(transactions.latest(user,dream.dreamId())).isEqualTo(first);
        assertThat(generator.calls).hasValue(1); assertThat(count("SETTLE")).isEqualTo(1);
    }
    @Test void imageSaveFailureRollsBackSettlementThenReleasesReservation() {
        fund(10); var dream=ready(); generator.pending=new CompletableFuture<>();
        var call=images.generate(user,dream.dreamId(),request(dream));
        var running=transactions.latest(user,dream.dreamId());
        jdbc.execute("alter table dream_images add constraint credit_image_save_failure check(id<>"+running.imageId()+" or status<>'COMPLETED')");
        try {
            generator.pending.complete(ImagePayloadTest.png()); assertThatThrownBy(() -> await(call)).isInstanceOf(BusinessException.class);
            assertThat(count("SETTLE")).isZero(); assertThat(count("RELEASE")).isEqualTo(1);
            assertThat(credits.balance(user).available()).isEqualTo(10); assertThat(storage.stored).isEmpty();
        } finally { jdbc.execute("alter table dream_images drop constraint credit_image_save_failure"); }
    }
    void expireImage(Long image) { jdbc.update("update dream_images set lease_until=? where id=?", java.sql.Timestamp.from(Instant.EPOCH),image); }
    void expireCredit(Long reservation) { jdbc.update("update credit_reservations set expires_at=? where id=?",java.sql.Timestamp.from(Instant.EPOCH),reservation); }

    @Test void restartRecoveryReleasesCreditAndRejectsLateUploadWithoutAnotherRequest() {
        fund(10); var dream=ready(); generator.pending=new CompletableFuture<>();
        var call=images.generate(user,dream.dreamId(),request(dream)); var running=transactions.latest(user,dream.dreamId());
        expireImage(running.imageId()); expireCredit(reservation(running.imageId()));
        recovery.recoverExpired(); recovery.recoverExpired();
        assertThat(transactions.latest(user,dream.dreamId()).failureCode()).isEqualTo("ATTEMPT_EXPIRED");
        assertThat(credits.balance(user).available()).isEqualTo(10); assertThat(credits.balance(user).reserved()).isZero();
        assertThat(count("RELEASE")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from image_cleanup_requests where user_id=?",Long.class,user)).isEqualTo(2);
        generator.pending.complete(ImagePayloadTest.png()); assertThat(await(call).status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(storage.stored).isEmpty(); assertThat(count("SETTLE")).isZero();
    }
    @Test void deletingExpiredDreamReleasesReservationInSameTransaction() {
        fund(10); var dream=ready(); generator.pending=new CompletableFuture<>();
        var call=images.generate(user,dream.dreamId(),request(dream));
        expireImage(generator.last.imageId()); dreams.delete(user,dream.dreamId(),dream.revision());
        assertThat(credits.balance(user).available()).isEqualTo(10); assertThat(count("RELEASE")).isEqualTo(1);
        generator.pending.complete(ImagePayloadTest.png()); assertThatThrownBy(() -> await(call)).isInstanceOf(BusinessException.class);
        assertThat(storage.stored).isEmpty();
    }
    @Test void cleanupRegistrationFailureRollsBackDeletionAndCreditRelease() {
        fund(10); var dream=ready(); generator.pending=new CompletableFuture<>();
        var call=images.generate(user,dream.dreamId(),request(dream)); var image=generator.last.imageId(); expireImage(image);
        jdbc.execute("alter table image_cleanup_requests add constraint credit_delete_cleanup_failure check(user_id<>"+user+")");
        try {
            assertThatThrownBy(() -> dreams.delete(user,dream.dreamId(),dream.revision())).isInstanceOf(RuntimeException.class);
            assertThat(credits.balance(user).reserved()).isEqualTo(2); assertThat(count("RELEASE")).isZero();
            assertThat(transactions.latest(user,dream.dreamId()).status()).isEqualTo(GenerationStatus.PROCESSING);
        } finally { jdbc.execute("alter table image_cleanup_requests drop constraint credit_delete_cleanup_failure"); }
        dreams.delete(user,dream.dreamId(),dream.revision());
        assertThat(credits.balance(user).reserved()).isZero();
        generator.pending.complete(ImagePayloadTest.png()); assertThatThrownBy(() -> await(call)).isInstanceOf(BusinessException.class);
    }
    @Test void concurrentRecoveryWorkersReleaseOnlyOnce() throws Exception {
        fund(10); var dream=ready(); generator.pending=new CompletableFuture<>();
        var call=images.generate(user,dream.dreamId(),request(dream)); var image=generator.last.imageId(); expireImage(image);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(() -> recovery.recoverExpired()); var two=pool.submit(() -> recovery.recoverExpired());
            one.get(5,TimeUnit.SECONDS); two.get(5,TimeUnit.SECONDS);
        }
        assertThat(count("RELEASE")).isEqualTo(1); assertThat(credits.balance(user).reserved()).isZero();
        generator.pending.complete(ImagePayloadTest.png()); await(call); assertThat(count("SETTLE")).isZero();
    }
    @Test void failedRecoveryRemainsRetryableWithoutDoubleRelease() {
        fund(10); var dream=ready(); generator.pending=new CompletableFuture<>();
        var call=images.generate(user,dream.dreamId(),request(dream)); var image=generator.last.imageId(); expireImage(image);
        jdbc.execute("alter table credit_ledger add constraint credit_recovery_failure check(user_id<>"+user+" or kind<>'RELEASE')");
        try {
            recovery.recoverExpired();
            assertThat(credits.balance(user).reserved()).isEqualTo(2);
            assertThat(transactions.latest(user,dream.dreamId()).status()).isEqualTo(GenerationStatus.PROCESSING);
        } finally { jdbc.execute("alter table credit_ledger drop constraint credit_recovery_failure"); }
        recovery.recoverExpired(); assertThat(credits.balance(user).reserved()).isZero(); assertThat(count("RELEASE")).isEqualTo(1);
        generator.pending.complete(ImagePayloadTest.png()); await(call);
    }
    @Test void orphanedExpiredReservationIsAlsoRecovered() {
        fund(10);
        var orphan=reservations.reserve(user,"orphan-attempt:"+user,CreditUsage.DREAM_IMAGE_GENERATION,"orphan-target:"+user,Instant.now().plusSeconds(60));
        expireCredit(orphan.reservationId()); recovery.recoverExpired();
        assertThat(credits.balance(user).reserved()).isZero(); assertThat(credits.balance(user).available()).isEqualTo(10);
        assertThat(count("RELEASE")).isEqualTo(1);
    }

    java.net.http.HttpResponse<String> post(DreamResponse dream,String access) throws Exception {
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+"/api/v1/dreams/"+dream.dreamId()+"/image"))
            .header("Content-Type","application/json").header("Authorization","Bearer "+access)
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"revision\":"+dream.revision()+",\"style\":\"test-style\"}")).build();
        return java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
    }
    @Test void httpReportsInsufficientAndUnconfiguredCostBeforeProviderCall() throws Exception {
        var dream=ready(); String access=tokens.login(user).response().accessToken();
        var insufficient=post(dream,access);
        assertThat(insufficient.statusCode()).isEqualTo(409); assertThat(insufficient.body()).contains("CREDIT_409_1");
        fund(10);
        org.mockito.Mockito.doThrow(new BusinessException(CreditErrorCode.UNAVAILABLE))
            .when(creditProperties).cost(CreditUsage.DREAM_IMAGE_GENERATION);
        var unavailable=post(dream,access);
        assertThat(unavailable.statusCode()).isEqualTo(503); assertThat(unavailable.body()).contains("CREDIT_503_1");
        assertThat(generator.calls).hasValue(0); assertThat(credits.balance(user).reserved()).isZero();
    }
    @Test void httpAndOpenApiPreserveSuccessSchemaAndChargingErrors() throws Exception {
        fund(10); var dream=ready(); String access=tokens.login(user).response().accessToken();
        var response=post(dream,access); assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("COMPLETED").doesNotContain("creditReservationId","storageKey");
        var docs=java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+"/v3/api-docs")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(docs.statusCode()).isEqualTo(200);
        var json=new tools.jackson.databind.ObjectMapper().readTree(docs.body());
        var operation=json.path("paths").path("/api/v1/dreams/{dreamId}/image").path("post");
        assertThat(operation.path("responses").path("200").path("content").path("application/json").path("schema").toString()).contains("$ref");
        assertThat(operation.path("responses").path("409").path("description").asText()).contains("CREDIT_409_1","CREDIT_409_5");
        assertThat(operation.path("responses").path("503").path("description").asText()).contains("CREDIT_503_1");
    }

}
