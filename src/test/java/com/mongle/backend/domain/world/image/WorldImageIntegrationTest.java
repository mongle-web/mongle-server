package com.mongle.backend.domain.world.image;

import static com.mongle.backend.domain.dream.gateway.DreamAiTestAwait.await;
import static org.assertj.core.api.Assertions.*;
import com.mongle.backend.domain.auth.service.TokenService;
import com.mongle.backend.domain.credit.*;
import com.mongle.backend.domain.dream.analysis.*;
import com.mongle.backend.domain.dream.dto.*;
import com.mongle.backend.domain.dream.entity.DreamEmotion;
import com.mongle.backend.domain.dream.image.*;
import com.mongle.backend.domain.dream.image.openai.OpenAiTestServer;
import com.mongle.backend.domain.dream.service.DreamService;
import com.mongle.backend.domain.dream.story.*;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.domain.world.bridge.BridgeGenerator;
import com.mongle.backend.domain.world.composition.*;
import com.mongle.backend.domain.world.folder.*;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.*;
import java.net.http.*;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:world-images;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "mongle.credit.enabled=true","mongle.credit.world-image-generation=2","mongle.credit.world-image-regeneration=3",
    "mongle.image.styles[0]=test-style"})
@ActiveProfiles("test") @Import(WorldImageIntegrationTest.Config.class)
class WorldImageIntegrationTest {
    @TestConfiguration static class Config {
        @Bean @Primary StructureGenerator structure() { return input->CompletableFuture.completedFuture("""
            {"generatedTitle":"Ocean","displayKeywords":["ocean"],"elements":[],"scenes":[{"sequence":1,"content":"Ocean","disconnectedFromPrevious":false,"elementKeys":[]}]}
            """); }
        @Bean @Primary StoryGenerator story() { return input->CompletableFuture.completedFuture(JsonMapper.builder().build().writeValueAsString(
                new StoryResult(List.of(new StoryResult.Section(1,StoryResult.Kind.SCENE,1,input.originalText()))))); }
        @Bean @Primary BridgeGenerator bridge() { return input->CompletableFuture.completedFuture("{\"content\":\"Across the sea\"}"); }
        @Bean @Primary FakeGenerator generator() { return new FakeGenerator(); }
        @Bean @Primary FakeStore store() { return new FakeStore(); }
    }
    static class FakeGenerator implements ImageGenerator {
        final AtomicInteger calls=new AtomicInteger();
        volatile CompletableFuture<byte[]> pending;
        volatile byte[] output=OpenAiTestServer.image("png");
        volatile Input last;
        public CompletableFuture<byte[]> generate(Input input) {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calls.incrementAndGet();last=input;
            return pending!=null?pending:CompletableFuture.completedFuture(output);
        }
    }
    static class FakeStore implements ImageAssetStore {
        final Map<String,ImagePayload> stored=new ConcurrentHashMap<>();
        volatile boolean failPut,failDelete;
        public void put(String key,ImagePayload payload) {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if(failPut) throw new IllegalStateException("private storage failure"); stored.put(key,payload);
        }
        public void delete(String key) { if(failDelete) throw new IllegalStateException("private storage failure"); stored.remove(key); }
        public DownloadUrl temporaryUrl(String key,Duration lifetime) { return new DownloadUrl("https://assets.test/image",Instant.now().plusSeconds(60)); }
    }
    @Autowired UserRepository users;
    @Autowired DreamService dreams;
    @Autowired DreamStructureService analyses;
    @Autowired DreamStoryService stories;
    @Autowired WorldFolderService folders;
    @Autowired WorldCompositionTransactions worlds;
    @Autowired WorldCompositionWorker worker;
    @Autowired WorldImageService images;
    @Autowired WorldImageTransactions transactions;
    @Autowired CreditService credits;
    @Autowired CreditReservationService reservations;
    @Autowired ImageRecovery recovery;
    @Autowired ImageCleanupTransactions cleanup;
    @Autowired ImageCleanupWorkTransactions cleanupWork;
    @Autowired FakeGenerator generator;
    @Autowired FakeStore storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenService tokens;
    @MockitoSpyBean WorldImageProperties properties;
    @MockitoSpyBean CreditProperties creditProperties;
    @Value("${local.server.port}") int port;
    Long user;
    @BeforeEach void setup() {
        user=users.saveAndFlush(User.create(UUID.randomUUID()+"@test.com","WorldImage")).getId();
        generator.calls.set(0);generator.pending=null;generator.output=OpenAiTestServer.image("png");generator.last=null;
        storage.stored.clear();storage.failPut=false;storage.failDelete=false;
    }
    @AfterEach void release() { if(generator.pending!=null) generator.pending.complete(OpenAiTestServer.image("png")); }
    void fund() { credits.grant(user,CreditSource.PAYMENT,"world-test:"+user,100); }
    DreamResponse ready(int day) {
        var d=dreams.create(user,new DreamCreateRequest(LocalDate.of(2026,10,day),"Dream "+day));
        d=dreams.complete(user,d.dreamId(),new DreamEmotionsRequest(d.revision(),List.of(DreamEmotion.HAPPY)));
        await(analyses.analyze(user,d.dreamId(),d.revision()));d=dreams.get(user,d.dreamId());
        await(stories.generate(user,d.dreamId(),new StoryRequest(d.revision(),false,null)));return dreams.get(user,d.dreamId());
    }
    WorldFolderResponse world(DreamResponse... ds) throws Exception {
        var f=folders.create(user,"Ocean");for(var d:ds) f=folders.select(user,f.worldId(),d.dreamId(),f.revision());
        build(f.worldId());return folders.get(user,f.worldId());
    }
    void build(Long world) throws Exception {
        worlds.build(user,world,new WorldBuildRequest(folders.get(user,world).revision(),worlds.get(user,world).compositionRevision()));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<deadline) {
            worker.poll();var state=worlds.get(user,world);
            if(state.status()==GenerationStatus.COMPLETED) return;
            if(state.status()==GenerationStatus.FAILED) throw new AssertionError(state.failureCode());
            Thread.sleep(5);
        }
        throw new AssertionError("World did not finish");
    }
    WorldImageRequest request(Long world) { return new WorldImageRequest(worlds.get(user,world).compositionRevision(),"test-style",null,false,null); }
    WorldImageRequest regenerate(Long world) { return new WorldImageRequest(worlds.get(user,world).compositionRevision(),"test-style",null,true,transactions.latest(user,world).imageVersion()); }
    WorldImageResponse generate(Long world) { return await(images.generate(user,world,request(world))); }
    long ledger(String kind) { return jdbc.queryForObject("select count(*) from credit_ledger where user_id=? and kind=?",Long.class,user,kind); }
    void error(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,Object code) {
        assertThatThrownBy(action).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(code);
    }

    @Test void missingRegenerationPriceRejectsFreeAttemptWithoutChangingImageSourcesOrCredits() throws Exception {
        credits.grant(user,CreditSource.PAYMENT,"world-price:"+user,2);
        var f=world(ready(1),ready(2));var first=generate(f.worldId());
        var persisted=jdbc.queryForMap("select * from world_images where id=?",first.imageId());
        var sourceRows=jdbc.queryForList("select * from world_image_sources where image_id=? order by id",first.imageId());
        var storedKeys=Set.copyOf(storage.stored.keySet());
        org.mockito.Mockito.doReturn(null).when(creditProperties).configuredCost(CreditUsage.WORLD_IMAGE_REGENERATION);

        error(()->images.generate(user,f.worldId(),regenerate(f.worldId())),CreditErrorCode.UNAVAILABLE);
        var unchanged=transactions.latest(user,f.worldId());
        assertThat(unchanged.freeRegenerationAvailable()).isTrue();assertThat(unchanged.nextRegenerationCost()).isNull();
        assertThat(jdbc.queryForMap("select * from world_images where id=?",first.imageId())).isEqualTo(persisted);
        assertThat(jdbc.queryForList("select * from world_image_sources where image_id=? order by id",first.imageId())).isEqualTo(sourceRows);
        assertThat(credits.balance(user).available()).isZero();assertThat(credits.balance(user).reserved()).isZero();
        assertThat(ledger("RESERVE")).isEqualTo(1);assertThat(ledger("SETTLE")).isEqualTo(1);assertThat(ledger("RELEASE")).isZero();
        assertThat(generator.calls).hasValue(1);assertThat(storage.stored.keySet()).isEqualTo(storedKeys);
        assertThat(generate(f.worldId())).isEqualTo(unchanged);

        org.mockito.Mockito.doCallRealMethod().when(creditProperties).configuredCost(CreditUsage.WORLD_IMAGE_REGENERATION);
        var free=await(images.generate(user,f.worldId(),regenerate(f.worldId())));
        assertThat(free.freeRegenerationAvailable()).isFalse();assertThat(credits.balance(user).available()).isZero();
        assertThat(generator.calls).hasValue(2);assertThat(ledger("RESERVE")).isEqualTo(1);
    }

    @Test void freeRegenerationAtZeroBalanceIsSerializedAcrossConcurrentRequests() throws Exception {
        credits.grant(user,CreditSource.PAYMENT,"world-zero:"+user,2);
        var f=world(ready(1),ready(2));var first=generate(f.worldId());
        generator.pending=new CompletableFuture<>();var request=regenerate(f.worldId());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->images.generate(user,f.worldId(),request));
            var two=pool.submit(()->images.generate(user,f.worldId(),request));
            var call1=one.get(5,TimeUnit.SECONDS);var call2=two.get(5,TimeUnit.SECONDS);
            assertThat(generator.calls).hasValue(2);assertThat(credits.balance(user).reserved()).isZero();
            generator.pending.complete(OpenAiTestServer.image("png"));await(call1);await(call2);
        }
        var done=transactions.latest(user,f.worldId());
        assertThat(done.freeRegenerationAvailable()).isFalse();assertThat(done.nextRegenerationCost()).isEqualTo(3);
        assertThat(ledger("SETTLE")).isEqualTo(1);assertThat(credits.balance(user).available()).isZero();
        error(()->images.generate(user,f.worldId(),regenerate(f.worldId())),CreditErrorCode.INSUFFICIENT);
        assertThat(generator.calls).hasValue(2);
    }

    @Test void expiredFreeAttemptCannotConsumeAllowanceOrOverwriteItsRetry() throws Exception {
        fund();var f=world(ready(1),ready(2));generate(f.worldId());generator.pending=new CompletableFuture<>();
        var pending=images.generate(user,f.worldId(),regenerate(f.worldId()));var oldInput=generator.last;
        jdbc.update("update world_images set lease_until=? where id=?",java.sql.Timestamp.from(Instant.EPOCH),oldInput.imageId());
        recovery.recoverExpired();recovery.recoverExpired();
        assertThat(transactions.latest(user,f.worldId()).freeRegenerationAvailable()).isTrue();
        generator.pending.complete(OpenAiTestServer.image("png"));assertThat(await(pending).failureCode()).isEqualTo("ATTEMPT_EXPIRED");
        generator.pending=null;
        var done=await(images.generate(user,f.worldId(),regenerate(f.worldId())));
        assertThat(done.freeRegenerationAvailable()).isFalse();assertThat(ledger("SETTLE")).isEqualTo(1);
        assertThat(transactions.finish(oldInput,"world-images/discarded",ImagePayload.parse(OpenAiTestServer.image("png"))).stored()).isFalse();
    }

    @Test void compositionChangesAndDreamDeletionDoNotResetUsedFreeAllowance() throws Exception {
        fund();var a=ready(1);var b=ready(2);var f=world(a,b);generate(f.worldId());
        await(images.generate(user,f.worldId(),regenerate(f.worldId())));
        build(f.worldId());
        assertThat(transactions.latest(user,f.worldId()).freeRegenerationAvailable()).isFalse();
        dreams.delete(user,b.dreamId(),b.revision());
        var changed=transactions.latest(user,f.worldId());
        assertThat(changed.freeRegenerationAvailable()).isFalse();assertThat(changed.nextRegenerationCost()).isEqualTo(3);
        assertThat(generator.calls).hasValue(2);
        error(()->images.generate(user,f.worldId(),regenerate(f.worldId())),WorldImageErrorCode.COMPOSITION_REQUIRED);
        assertThat(credits.balance(user).available()).isEqualTo(98);assertThat(generator.calls).hasValue(2);
        var c=ready(3);folders.select(user,f.worldId(),c.dreamId(),folders.get(user,f.worldId()).revision());build(f.worldId());
        var paid=await(images.generate(user,f.worldId(),regenerate(f.worldId())));
        assertThat(paid.status()).isEqualTo(GenerationStatus.COMPLETED);assertThat(credits.balance(user).available()).isEqualTo(95);
    }

    @Test void usesPublishedDateOrderedStoriesRatherThanChangedSelectionAndChargesOnce() throws Exception {
        fund();var a=ready(1);var b=ready(2);var c=ready(3);var f=world(b,a);
        f=folders.exclude(user,f.worldId(),a.dreamId(),f.revision());folders.select(user,f.worldId(),c.dreamId(),f.revision());
        var result=generate(f.worldId());assertThat(result.status()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(generator.last.sections()).isEmpty();assertThat(generator.last.worldStories()).hasSize(2);
        assertThat(generator.last.worldStories().get(0).sections().getFirst().content()).isEqualTo("Dream 1");
        assertThat(generator.last.worldStories().get(1).bridgeBefore()).isEqualTo("Across the sea");
        assertThat(credits.balance(user).available()).isEqualTo(98);assertThat(credits.balance(user).reserved()).isZero();
        assertThat(generate(f.worldId())).isEqualTo(result);assertThat(generator.calls).hasValue(1);
        assertThat(ledger("RESERVE")).isEqualTo(1);assertThat(ledger("SETTLE")).isEqualTo(1);
        assertThat(jdbc.queryForList("select dream_id from world_image_sources where image_id=? and candidate=false order by dream_id",Long.class,result.imageId()))
                .containsExactly(a.dreamId(),b.dreamId());
    }
    @Test void pendingDuplicatesDoNotReserveOrCallAgainAndCompletionIsIdempotent() throws Exception {
        fund();var f=world(ready(1),ready(2));generator.pending=new CompletableFuture<>();
        var pending=images.generate(user,f.worldId(),request(f.worldId()));
        var duplicate=generate(f.worldId());assertThat(duplicate.status()).isEqualTo(GenerationStatus.PROCESSING);
        assertThat(generator.calls).hasValue(1);assertThat(ledger("RESERVE")).isEqualTo(1);assertThat(credits.balance(user).reserved()).isEqualTo(2);
        generator.pending.complete(OpenAiTestServer.image("png"));var result=await(pending);
        transactions.finish(generator.last,storage.stored.keySet().iterator().next(),ImagePayload.parse(OpenAiTestServer.image("png")));
        assertThat(result.status()).isEqualTo(GenerationStatus.COMPLETED);assertThat(ledger("SETTLE")).isEqualTo(1);
    }
    @Test void firstRegenerationIsFreeThenPaidAndQueuesPreviousFile() throws Exception {
        fund();var f=world(ready(1),ready(2));var first=generate(f.worldId());String old=storage.stored.keySet().iterator().next();
        var result=await(images.generate(user,f.worldId(),regenerate(f.worldId())));
        assertThat(result.imageId()).isEqualTo(first.imageId());assertThat(credits.balance(user).available()).isEqualTo(98);
        assertThat(first.freeRegenerationAvailable()).isTrue();assertThat(first.nextRegenerationCost()).isZero();
        assertThat(result.freeRegenerationAvailable()).isFalse();assertThat(result.nextRegenerationCost()).isEqualTo(3);
        assertThat(ledger("SETTLE")).isEqualTo(1);
        assertThat(await(images.generate(user,f.worldId(),regenerate(f.worldId()))).status()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(credits.balance(user).available()).isEqualTo(95);assertThat(ledger("SETTLE")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select reason from image_cleanup_requests where storage_key=?",String.class,old)).isEqualTo("REPLACED_RESULT");
        assertThat(jdbc.queryForObject("select count(*) from world_image_sources where image_id=? and candidate=false",Long.class,first.imageId())).isEqualTo(2);
        assertThat(transactions.latest(user,f.worldId()).hasPreviousResult()).isFalse();
    }
    @Test void failedRegenerationKeepsPreviousResultAndReleasesCredits() throws Exception {
        fund();var f=world(ready(1),ready(2));var first=generate(f.worldId());generator.output=new byte[]{1};
        var failed=await(images.generate(user,f.worldId(),regenerate(f.worldId())));
        assertThat(failed.failureCode()).isEqualTo("INVALID_OUTPUT");assertThat(failed.hasPreviousResult()).isTrue();
        assertThat(failed.freeRegenerationAvailable()).isTrue();assertThat(failed.nextRegenerationCost()).isZero();
        assertThat(failed.resultCompositionRevision()).isEqualTo(first.resultCompositionRevision());
        assertThat(credits.balance(user).available()).isEqualTo(98);assertThat(credits.balance(user).reserved()).isZero();
        assertThat(images.downloadUrl(user,f.worldId()).url()).startsWith("https://");
    }
    @Test void editsAndDeletionKeepImageIncludingOneAndZeroDreamWorlds() throws Exception {
        fund();var a=ready(1);var b=ready(2);var f=world(a,b);generate(f.worldId());
        var edit=new DreamUpdateRequest();edit.setRevision(b.revision());edit.setOriginalText("Edited B");dreams.update(user,b.dreamId(),edit);
        assertThat(transactions.latest(user,f.worldId()).sourceChanged()).isTrue();assertThat(generator.calls).hasValue(1);
        dreams.delete(user,b.dreamId(),dreams.get(user,b.dreamId()).revision());
        var changed=transactions.latest(user,f.worldId());assertThat(changed.sourceDeleted()).isTrue();assertThat(changed.compositionChanged()).isTrue();
        assertThat(storage.stored).hasSize(1);assertThat(images.downloadUrl(user,f.worldId()).url()).startsWith("https://");
        var persisted=jdbc.queryForMap("select * from world_images where id=?",changed.imageId());
        var sourceRows=jdbc.queryForList("select * from world_image_sources where image_id=? order by id",changed.imageId());
        var balance=credits.balance(user);var ledgerCount=ledger("RESERVE")+ledger("SETTLE")+ledger("RELEASE");
        error(()->images.generate(user,f.worldId(),regenerate(f.worldId())),WorldImageErrorCode.COMPOSITION_REQUIRED);
        assertThat(jdbc.queryForMap("select * from world_images where id=?",changed.imageId())).isEqualTo(persisted);
        assertThat(jdbc.queryForList("select * from world_image_sources where image_id=? order by id",changed.imageId())).isEqualTo(sourceRows);
        assertThat(credits.balance(user)).isEqualTo(balance);assertThat(ledger("RESERVE")+ledger("SETTLE")+ledger("RELEASE")).isEqualTo(ledgerCount);
        assertThat(transactions.latest(user,f.worldId()).freeRegenerationAvailable()).isTrue();
        assertThat(transactions.latest(user,f.worldId()).sourceDeleted()).isTrue();assertThat(generator.calls).hasValue(1);
        dreams.delete(user,a.dreamId(),dreams.get(user,a.dreamId()).revision());
        assertThat(transactions.latest(user,f.worldId()).sourceDeleted()).isTrue();
        error(()->images.generate(user,f.worldId(),regenerate(f.worldId())),WorldImageErrorCode.COMPOSITION_REQUIRED);
        assertThat(generator.calls).hasValue(1);assertThat(folders.get(user,f.worldId()).selectedDreamCount()).isZero();
        assertThat(storage.stored).hasSize(1);assertThat(images.downloadUrl(user,f.worldId()).url()).startsWith("https://");
    }
    @Test void firstImageWithOneOrZeroPublishedDreamsNeverCreatesAnAttemptOrReservesCredits() throws Exception {
        fund();var a=ready(1);var b=ready(2);var f=world(a,b);
        dreams.delete(user,b.dreamId(),b.revision());
        error(()->images.generate(user,f.worldId(),request(f.worldId())),WorldImageErrorCode.COMPOSITION_REQUIRED);
        assertThat(jdbc.queryForObject("select count(*) from world_images where world_id=?",Long.class,f.worldId())).isZero();
        assertThat(generator.calls).hasValue(0);assertThat(storage.stored).isEmpty();assertThat(ledger("RESERVE")).isZero();
        dreams.delete(user,a.dreamId(),a.revision());
        error(()->images.generate(user,f.worldId(),request(f.worldId())),WorldImageErrorCode.COMPOSITION_REQUIRED);
        assertThat(jdbc.queryForObject("select count(*) from world_images where world_id=?",Long.class,f.worldId())).isZero();
        assertThat(credits.balance(user).available()).isEqualTo(100);assertThat(credits.balance(user).reserved()).isZero();
        assertThat(generator.calls).hasValue(0);assertThat(ledger("RESERVE")).isZero();
    }

    @Test void deletionMarksOldImageEvenAfterDreamIsRemovedFromCurrentComposition() throws Exception {
        fund();var a=ready(1);var b=ready(2);var c=ready(3);var f=world(a,b,c);generate(f.worldId());
        folders.exclude(user,f.worldId(),b.dreamId(),f.revision());build(f.worldId());
        dreams.delete(user,b.dreamId(),dreams.get(user,b.dreamId()).revision());
        assertThat(transactions.latest(user,f.worldId()).sourceDeleted()).isTrue();assertThat(generator.calls).hasValue(1);
    }
    @Test void dreamDeletionDuringGenerationReleasesReservationAndDiscardsLateUpload() throws Exception {
        fund();var a=ready(1);var b=ready(2);var f=world(a,b);generator.pending=new CompletableFuture<>();
        var pending=images.generate(user,f.worldId(),request(f.worldId()));dreams.delete(user,b.dreamId(),b.revision());
        assertThat(credits.balance(user).available()).isEqualTo(100);assertThat(credits.balance(user).reserved()).isZero();
        generator.pending.complete(OpenAiTestServer.image("png"));assertThat(await(pending).failureCode()).isEqualTo("SOURCE_DELETED");
        assertThat(storage.stored).isEmpty();assertThat(ledger("SETTLE")).isZero();
    }
    @Test void compositionReplacementDuringGenerationCannotPublishStaleImage() throws Exception {
        fund();var f=world(ready(1),ready(2));generator.pending=new CompletableFuture<>();
        var pending=images.generate(user,f.worldId(),request(f.worldId()));build(f.worldId());
        generator.pending.complete(OpenAiTestServer.image("png"));assertThat(await(pending).failureCode()).isEqualTo("COMPOSITION_CHANGED");
        assertThat(storage.stored).isEmpty();assertThat(credits.balance(user).available()).isEqualTo(100);
    }
    @Test void folderDeletionCleansImageAndCannotBeResurrectedByLateCompletion() throws Exception {
        fund();var f=world(ready(1),ready(2));generate(f.worldId());generator.pending=new CompletableFuture<>();
        var pending=images.generate(user,f.worldId(),regenerate(f.worldId()));folders.delete(user,f.worldId(),f.revision());
        generator.pending.complete(OpenAiTestServer.image("png"));assertThatThrownBy(()->await(pending)).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("select count(*) from world_images where world_id=?",Long.class,f.worldId())).isZero();
        assertThat(credits.balance(user).available()).isEqualTo(98);assertThat(credits.balance(user).reserved()).isZero();
        assertThat(jdbc.queryForList("select reason from image_cleanup_requests where user_id=?",String.class,user)).contains("WORLD_DELETED");
    }
    @Test void insufficientFundsAndInputLimitFailBeforeProviderCall() throws Exception {
        var f=world(ready(1),ready(2));error(()->images.generate(user,f.worldId(),request(f.worldId())),CreditErrorCode.INSUFFICIENT);
        assertThat(generator.calls).hasValue(0);assertThat(ledger("RESERVE")).isZero();
        fund();org.mockito.Mockito.doReturn(1).when(properties).maxInputCharacters();
        error(()->images.generate(user,f.worldId(),request(f.worldId())),WorldImageErrorCode.INPUT_TOO_LARGE);
        assertThat(generator.calls).hasValue(0);assertThat(ledger("RESERVE")).isZero();
    }
    @Test void expiryRecoveryReleasesOnceWithoutRecallingAi() throws Exception {
        fund();var f=world(ready(1),ready(2));generator.pending=new CompletableFuture<>();
        var pending=images.generate(user,f.worldId(),request(f.worldId()));var running=transactions.latest(user,f.worldId());
        jdbc.update("update world_images set lease_until=? where id=?",java.sql.Timestamp.from(Instant.EPOCH),running.imageId());
        recovery.recoverExpired();recovery.recoverExpired();
        assertThat(transactions.latest(user,f.worldId()).failureCode()).isEqualTo("ATTEMPT_EXPIRED");
        generator.pending.complete(OpenAiTestServer.image("png"));await(pending);
        assertThat(generator.calls).hasValue(1);assertThat(credits.balance(user).available()).isEqualTo(100);assertThat(ledger("RELEASE")).isEqualTo(1);
        assertThat(storage.stored).isEmpty();
    }
    @Test void persistenceFailureRollsBackSettlementAndPreservesPreviousImage() throws Exception {
        fund();var f=world(ready(1),ready(2));var first=generate(f.worldId());generator.pending=new CompletableFuture<>();
        var pending=images.generate(user,f.worldId(),regenerate(f.worldId()));
        jdbc.execute("alter table world_images add constraint fail_world_image_publish check(id<>"+first.imageId()+" or status<>'COMPLETED')");
        try {
            generator.pending.complete(OpenAiTestServer.image("png"));assertThatThrownBy(()->await(pending)).isInstanceOf(BusinessException.class);
            assertThat(transactions.latest(user,f.worldId()).hasPreviousResult()).isTrue();assertThat(storage.stored).hasSize(1);
            assertThat(transactions.latest(user,f.worldId()).freeRegenerationAvailable()).isTrue();
            assertThat(ledger("SETTLE")).isEqualTo(1);assertThat(credits.balance(user).available()).isEqualTo(98);
        } finally {jdbc.execute("alter table world_images drop constraint fail_world_image_publish");}
    }
    @Test void cleanupProtectsSuccessfulWorldKeyAndExpiresOnlyDiscardedAttempt() throws Exception {
        fund();var f=world(ready(1),ready(2));var image=generate(f.worldId());var key=storage.stored.keySet().iterator().next();
        cleanup.recordDiscardedUpload(user,image.imageId(),key);
        var id=jdbc.queryForObject("select id from image_cleanup_requests where storage_key=?",Long.class,key);
        assertThat(cleanupWork.claim(id)).isEmpty();assertThat(storage.stored).containsKey(key);
        folders.delete(user,f.worldId(),f.revision());jdbc.update("update image_cleanup_requests set next_run_at=? where id=?",java.sql.Timestamp.from(Instant.EPOCH),id);
        var claim=cleanupWork.claim(id).orElseThrow();assertThat(cleanupWork.prepare(claim)).isTrue();
        storage.delete(claim.key());cleanupWork.complete(claim);assertThat(storage.stored).isEmpty();
    }
    @Test void unavailableNewGenerationDoesNotBlockFreeReuse() throws Exception {
        fund();var f=world(ready(1),ready(2));var image=generate(f.worldId());
        org.mockito.Mockito.doReturn(false).when(properties).enabled();
        assertThat(generate(f.worldId())).isEqualTo(image);
        error(()->images.generate(user,f.worldId(),regenerate(f.worldId())),ImageErrorCode.UNAVAILABLE);
        assertThat(generator.calls).hasValue(1);
    }
    @Test void releasedCreditCannotPublishAndStorageFailuresReleaseReservation() throws Exception {
        fund();var f=world(ready(1),ready(2));generator.pending=new CompletableFuture<>();
        var pending=images.generate(user,f.worldId(),request(f.worldId()));
        var image=transactions.latest(user,f.worldId());var reservation=jdbc.queryForObject("select credit_reservation_id from world_images where id=?",Long.class,image.imageId());
        reservations.release(user,reservation,CreditReservation.ReleaseReason.CANCELLED);
        generator.pending.complete(OpenAiTestServer.image("png"));assertThat(await(pending).failureCode()).isEqualTo("CREDIT_RESERVATION_EXPIRED");
        assertThat(storage.stored).isEmpty();generator.pending=null;storage.failPut=true;
        assertThat(generate(f.worldId()).failureCode()).isEqualTo("STORAGE_FAILED");assertThat(credits.balance(user).available()).isEqualTo(100);
    }
    @Test void noCompletedCompositionAndStaleVersionsNeverCallProvider() throws Exception {
        fund();var empty=folders.create(user,"Empty");error(()->images.generate(user,empty.worldId(),new WorldImageRequest(0L,"test-style",null,false,null)),WorldImageErrorCode.COMPOSITION_REQUIRED);
        var f=world(ready(1),ready(2));error(()->images.generate(user,f.worldId(),new WorldImageRequest(0L,"test-style",null,false,null)),WorldImageErrorCode.VERSION_CONFLICT);
        var result=generate(f.worldId());
        error(()->images.generate(user,f.worldId(),new WorldImageRequest(result.compositionRevision(),"test-style",null,true,result.imageVersion()-1)),WorldImageErrorCode.VERSION_CONFLICT);
        assertThat(generator.calls).hasValue(1);
    }
    @Test void httpContractsEnforceAuthenticationOwnershipValidationAndNoStore() throws Exception {
        fund();var f=world(ready(1),ready(2));String token=tokens.login(user).response().accessToken();
        var client=HttpClient.newHttpClient();String path="/api/v1/worlds/"+f.worldId()+"/image";
        assertThat(send(client,null,"GET",path,null).statusCode()).isEqualTo(401);
        assertThat(send(client,token,"POST",path,"{\"compositionRevision\":1,\"style\":\"test-style\",\"regenerate\":true}").statusCode()).isEqualTo(400);
        var response=send(client,token,"POST",path,"{\"compositionRevision\":1,\"style\":\"test-style\"}");
        assertThat(response.statusCode()).isEqualTo(200);assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.body()).doesNotContain("storageKey","world-images/");
        assertThat(send(client,token,"GET",path+"/download-url",null).statusCode()).isEqualTo(200);
        var other=users.saveAndFlush(User.create(UUID.randomUUID()+"@test.com","Other")).getId();
        assertThat(send(client,tokens.login(other).response().accessToken(),"GET",path,null).statusCode()).isEqualTo(404);
        var swagger=send(client,null,"GET","/v3/api-docs",null);
        assertThat(swagger.body()).contains("/api/v1/worlds/{worldId}/image","WorldImageRequest","WorldImageResponse");
    }
    HttpResponse<String> send(HttpClient client,String token,String method,String path,String body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));if(token!=null) request.header("Authorization","Bearer "+token);
        if(body!=null) request.header("Content-Type","application/json");
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
}
