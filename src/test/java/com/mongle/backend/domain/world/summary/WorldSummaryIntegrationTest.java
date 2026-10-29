package com.mongle.backend.domain.world.summary;

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
import com.mongle.backend.domain.world.bridge.*;
import com.mongle.backend.domain.world.composition.*;
import com.mongle.backend.domain.world.folder.*;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.BusinessException;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:world-summaries;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "mongle.world.summary.enabled=true",
            "mongle.world.summary.worker-enabled=false"
        })
@ActiveProfiles("test")
@Import(WorldSummaryIntegrationTest.Config.class)
public class WorldSummaryIntegrationTest {
    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        StructureGenerator summaryStructure() {
            return input ->
                    CompletableFuture.completedFuture(
                            WorldSummaryEvidenceTest.analysis("바다", "하늘"));
        }

        @Bean
        @Primary
        StoryGenerator summaryStory() {
            return input ->
                    CompletableFuture.completedFuture(
                            "{\"sections\":[{\"sequence\":1,\"kind\":\"SCENE\",\"sceneSequence\":1,\"content\":\"바다"
                                + " 위를 날아갔다\"}]}");
        }

        @Bean
        @Primary
        BridgeGenerator summaryBridge() {
            return input -> CompletableFuture.completedFuture("{\"content\":\"다음 바다에서 다시 날아갔다\"}");
        }

        @Bean
        @Primary
        public FakeGenerator summaryFakeGenerator() {
            return new FakeGenerator();
        }

        @Bean
        @Primary
        public MutableClock summaryClock() {
            return new MutableClock();
        }
    }

    public static class MutableClock extends Clock {
        volatile Instant now = Instant.now();

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    }

    public static class FakeGenerator implements WorldSummaryGenerator {
        AtomicInteger calls = new AtomicInteger();
        volatile boolean enabled = true;
        volatile String output = "{\"generatedTitle\":\"하늘을 건넌 바다\"}";
        volatile CompletableFuture<String> pending;
        volatile Input input;

        public boolean available() {
            return enabled;
        }

        public CompletableFuture<String> generate(Input value) {
            assertThat(
                            org.springframework.transaction.support
                                    .TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            input = value;
            calls.incrementAndGet();
            return pending != null ? pending : CompletableFuture.completedFuture(output);
        }
    }

    @Autowired WorldSummaryTransactions summaries;
    @Autowired WorldSummaryWorker summaryWorker;
    @Autowired WorldCompositionTransactions worlds;
    @Autowired WorldCompositionWorker worker;
    @Autowired WorldFolderService folders;
    @Autowired DreamService dreams;
    @Autowired DreamStructureService analyses;
    @Autowired DreamStoryService stories;
    @Autowired StoryTransactions storyTransactions;
    @Autowired UserRepository users;
    @Autowired TokenService tokens;
    @MockitoSpyBean JdbcTemplate jdbc;
    @Autowired EntityManagerFactory emf;
    @Autowired FakeGenerator generator;
    @Autowired MutableClock clock;
    @MockitoSpyBean WorldSummaryProperties properties;
    @MockitoSpyBean WorldSummaryRepository summaryRepository;

    @Value("${local.server.port}")
    int port;

    Long user;

    @BeforeEach
    void setup() {
        clock.now = Instant.now();
        user =
                users.saveAndFlush(User.create(UUID.randomUUID() + "@test.invalid", "세계관결과"))
                        .getId();
        generator.calls.set(0);
        generator.output = "{\"generatedTitle\":\"하늘을 건넌 바다\"}";
        generator.pending = null;
        generator.input = null;
        generator.enabled = true;
        org.mockito.Mockito.doReturn(true).when(properties).enabled();
        org.mockito.Mockito.doReturn(120000).when(properties).maxInputCharacters();
    }

    @AfterEach
    void release() throws Exception {
        jdbc.update(
                "update world_summaries set status='FAILED',lease_until=null where user_id=? and"
                        + " status in ('PENDING','PROCESSING')",
                user);
        if (generator.pending != null) generator.pending.complete(generator.output);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (summaryWorker.activeCount() > 0 && System.nanoTime() < end) Thread.sleep(5);
    }

    DreamResponse ready(int day) {
        var d =
                dreams.create(
                        user, new DreamCreateRequest(LocalDate.of(2026, 10, day), "바다에서 날아다녔다"));
        d =
                dreams.complete(
                        user,
                        d.dreamId(),
                        new DreamEmotionsRequest(d.revision(), List.of(DreamEmotion.HAPPY)));
        await(analyses.analyze(user, d.dreamId(), d.revision()));
        d = dreams.get(user, d.dreamId());
        await(stories.generate(user, d.dreamId(), new StoryRequest(d.revision(), false, null)));
        return dreams.get(user, d.dreamId());
    }

    Long folder(DreamResponse... ds) {
        var f = folders.create(user, "첫 꿈 제목 외 N개");
        for (var d : ds) f = folders.select(user, f.worldId(), d.dreamId(), f.revision());
        return f.worldId();
    }

    void build(Long world) throws Exception {
        worlds.build(
                user,
                world,
                new WorldBuildRequest(
                        folders.get(user, world).revision(),
                        worlds.get(user, world).compositionRevision()));
        pumpWorld(world);
    }

    void pumpWorld(Long world) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            worker.poll();
            var state = worlds.get(user, world);
            if (state.status() == GenerationStatus.COMPLETED) return;
            if (state.status() == GenerationStatus.FAILED) throw new AssertionError(state);
            Thread.sleep(5);
        } while (System.nanoTime() < end);
        throw new AssertionError("세계관 구성 시간 초과");
    }

    WorldSummaryResponse pump(Long world) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            summaryWorker.poll();
            var state = summaries.get(user, world);
            if (state.status() == WorldSummaryResponse.Status.COMPLETED
                    || state.status() == WorldSummaryResponse.Status.FAILED) return state;
            Thread.sleep(5);
        } while (System.nanoTime() < end);
        throw new AssertionError("세계관 결과 시간 초과");
    }

    void edit(DreamResponse d) {
        var r = new DreamUpdateRequest();
        r.setRevision(dreams.get(user, d.dreamId()).revision());
        r.setOriginalText("수정된 숲 속의 꿈");
        r.setEmotions(List.of(DreamEmotion.SAD));
        dreams.update(user, d.dreamId(), r);
    }

    long version(Long dream) {
        return jdbc.queryForObject(
                "select max(v.id) from dream_story_versions v join dream_stories s on"
                        + " s.id=v.story_id join dream_analyses a on a.id=s.analysis_id where"
                        + " a.source_dream_id=?",
                Long.class,
                dream);
    }

    WorldFolderCardResponse card(Long world) {
        return folders.list(user, null, 50, null).items().stream()
                .filter(item -> item.worldId().equals(world)).findFirst().orElseThrow();
    }

    @Test
    void listCardIncludesSavedSummaryAndOldestPublishedDreamWithoutCallingAiOrWritingState() throws Exception {
        var later = ready(2);
        var oldest = ready(1);
        var rename = new DreamUpdateRequest();
        rename.setRevision(oldest.revision()); rename.setTitle("첫 번째 꿈");
        dreams.update(user, oldest.dreamId(), rename);
        var world = folder(later, oldest);
        build(world);
        var pending = card(world);
        assertThat(pending.composedDreamCount()).isEqualTo(2);
        assertThat(pending.summary().status()).isEqualTo(WorldSummaryResponse.Status.PENDING);
        assertThat(pending.summary().generatedTitle()).isNull();
        var result = pump(world);
        var before = jdbc.queryForMap("select * from world_summaries where world_id=?", world);
        var listed = card(world);
        assertThat(listed.name()).isEqualTo("첫 꿈 제목 외 N개");
        assertThat(listed.selectedDreamCount()).isEqualTo(2);
        assertThat(listed.composedDreamCount()).isEqualTo(2);
        assertThat(listed.representativeDream().dreamId()).isEqualTo(oldest.dreamId());
        assertThat(listed.representativeDream().title()).isEqualTo("첫 번째 꿈");
        assertThat(listed.representativeDream().dreamedAt()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(listed.summary()).isEqualTo(result);
        assertThat(jdbc.queryForMap("select * from world_summaries where world_id=?", world)).isEqualTo(before);
        assertThat(generator.calls).hasValue(1);
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/worlds"))
                    .header("Authorization", "Bearer "+tokens.login(user).response().accessToken()).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("cache-control")).hasValue("no-store");
            var item = JsonMapper.builder().build().readTree(response.body()).at("/data/items/0");
            assertThat(item.get("composedDreamCount").asLong()).isEqualTo(2);
            assertThat(item.at("/summary/generatedTitle").asString()).isEqualTo(result.generatedTitle());
            assertThat(item.at("/summary/commonKeywords/0/keyword").asString()).isEqualTo("바다");
            assertThat(response.body()).doesNotContain("바다 위를 날아갔다", "attemptSettings", "resultJson");
        }
        rename.setRevision(dreams.get(user, oldest.dreamId()).revision()); rename.setTitle(null);
        dreams.update(user, oldest.dreamId(), rename);
        assertThat(card(world).representativeDream().title()).isNull();
        assertThat(generator.calls).hasValue(1);
    }

    @Test
    void listSeparatesSelectedPublishedAndPreviousSummaryCountsAcrossBuildAndFailure() throws Exception {
        var a = ready(1); var b = ready(2); var c = ready(3);
        var world = folder(a, b);
        build(world); pump(world);
        folders.select(user, world, c.dreamId(), folders.get(user, world).revision());
        var selected = card(world);
        assertThat(selected.selectedDreamCount()).isEqualTo(3);
        assertThat(selected.composedDreamCount()).isEqualTo(2);
        assertThat(selected.summary().sourceCount()).isEqualTo(2);
        worlds.build(user, world, new WorldBuildRequest(folders.get(user, world).revision(),
                worlds.get(user, world).compositionRevision()));
        assertThat(card(world).composedDreamCount()).isEqualTo(2);
        pumpWorld(world);
        var pending = card(world);
        assertThat(pending.composedDreamCount()).isEqualTo(3);
        assertThat(pending.summary().sourceCount()).isEqualTo(2);
        assertThat(pending.summary().status()).isEqualTo(WorldSummaryResponse.Status.PENDING);
        assertThat(pending.summary().refreshNeeded()).isTrue();
        assertThat(pending.summary().hasPreviousResult()).isTrue();
        assertThat(pending.summary().resultCompositionRevision()).isLessThan(pending.summary().compositionRevision());
        var step = summaries.claim(user, world);
        assertThat(card(world).summary().status()).isEqualTo(WorldSummaryResponse.Status.PROCESSING);
        summaries.complete(step, null, new IllegalStateException("provider failed"));
        var failed = card(world);
        assertThat(failed.composedDreamCount()).isEqualTo(3);
        assertThat(failed.summary().sourceCount()).isEqualTo(2);
        assertThat(failed.summary().status()).isEqualTo(WorldSummaryResponse.Status.FAILED);
        assertThat(failed.summary().hasPreviousResult()).isTrue();
        assertThat(generator.calls).hasValue(1);
        summaries.request(user, world); pump(world);
        var done = card(world);
        assertThat(done.composedDreamCount()).isEqualTo(3);
        assertThat(done.summary().sourceCount()).isEqualTo(3);
        assertThat(done.summary().refreshNeeded()).isFalse();
    }

    @Test
    void listKeepsZeroAndOneDreamFoldersAndDoesNotExposeDeletedSummaryKeywords() throws Exception {
        var a = ready(1); var b = ready(2);
        var world = folder(a, b);
        build(world); pump(world);
        dreams.delete(user, a.dreamId(), a.revision());
        var one = card(world);
        assertThat(one.composedDreamCount()).isEqualTo(1);
        assertThat(one.representativeDream().dreamId()).isEqualTo(b.dreamId());
        assertThat(one.summary().generatedTitle()).isNull();
        assertThat(one.summary().commonKeywords()).isEmpty();
        assertThat(one.summary().sourceCount()).isZero();
        summaries.request(user, world); pump(world);
        assertThat(card(world).summary().sourceCount()).isEqualTo(1);
        dreams.delete(user, b.dreamId(), b.revision());
        var empty = card(world);
        assertThat(empty.composedDreamCount()).isZero();
        assertThat(empty.representativeDream()).isNull();
        assertThat(empty.summary().status()).isEqualTo(WorldSummaryResponse.Status.EMPTY);
        assertThat(empty.summary().commonKeywords()).isEmpty();
        assertThat(empty.summary().generatedTitle()).isNull();
        assertThat(generator.calls).hasValue(2);
    }

    @Test
    void listUsesFrozenKeywordsAndMarksSourceChangesWithoutRegeneration() throws Exception {
        var a = ready(1);
        var world = folder(a, ready(2));
        build(world); var done = pump(world);
        edit(a);
        var listed = card(world).summary();
        assertThat(listed.commonKeywords()).isEqualTo(done.commonKeywords());
        assertThat(listed.recurringEmotions()).isEqualTo(done.recurringEmotions());
        assertThat(listed.sourceChanged()).isTrue();
        assertThat(listed.refreshNeeded()).isTrue();
        assertThat(listed.hasPreviousResult()).isTrue();
        assertThat(listed).isEqualTo(summaries.get(user, world));
        assertThat(generator.calls).hasValue(1);
    }

    @Test
    void listSuppressesMissingResultSourcesWithoutPersistingSanitization() throws Exception {
        var world = folder(ready(1), ready(2));
        build(world); pump(world);
        jdbc.update("update world_summary_sources set story_version_id=? where world_id=? and candidate=false",
                Long.MAX_VALUE, world);
        var before = jdbc.queryForMap("select * from world_summaries where world_id=?", world);
        var listed = card(world).summary();
        assertThat(listed.generatedTitle()).isNull();
        assertThat(listed.commonKeywords()).isEmpty();
        assertThat(listed.recurringEmotions()).isEmpty();
        assertThat(listed.sourceCount()).isZero();
        assertThat(listed.resultCompositionRevision()).isNull();
        assertThat(listed.generatedAt()).isNull();
        assertThat(jdbc.queryForMap("select * from world_summaries where world_id=?", world)).isEqualTo(before);
        assertThat(generator.calls).hasValue(1);
        assertThat(summaries.get(user, world).generatedTitle()).isNull();
    }

    @Test
    void listProjectsExpiredLeaseWithoutChangingStoredJobOrSendingAi() throws Exception {
        var world = folder(ready(1), ready(2));
        build(world); pump(world);
        folders.select(user, world, ready(3).dreamId(), folders.get(user, world).revision());
        build(world); summaries.claim(user, world);
        clock.now = clock.now.plusSeconds(121);
        var before = jdbc.queryForMap("select * from world_summaries where world_id=?", world);
        var listed = card(world).summary();
        assertThat(listed.status()).isEqualTo(WorldSummaryResponse.Status.FAILED);
        assertThat(listed.failureCode()).isEqualTo("ATTEMPT_EXPIRED");
        assertThat(listed.generatedTitle()).isEqualTo("하늘을 건넌 바다");
        assertThat(listed.hasPreviousResult()).isTrue();
        assertThat(jdbc.queryForMap("select * from world_summaries where world_id=?", world)).isEqualTo(before);
        assertThat(generator.calls).hasValue(1);
    }

    @Test
    void listIncludesUnbuiltAndNotGeneratedWorldsWithoutAutoQueueing() throws Exception {
        var empty = folders.create(user, "빈 폴더").worldId();
        var unbuilt = folder(ready(1));
        var built = folder(ready(2), ready(3));
        org.mockito.Mockito.doReturn(false).when(properties).enabled();
        build(built);
        for (Long world : List.of(empty, unbuilt, built)) {
            var listed = card(world);
            assertThat(listed.summary().status()).isEqualTo(WorldSummaryResponse.Status.NOT_GENERATED);
            assertThat(listed.summary().generatedTitle()).isNull();
            assertThat(listed.summary().commonKeywords()).isEmpty();
        }
        assertThat(card(empty).composedDreamCount()).isZero();
        assertThat(card(unbuilt).selectedDreamCount()).isEqualTo(1);
        assertThat(card(unbuilt).composedDreamCount()).isZero();
        assertThat(card(unbuilt).representativeDream()).isNull();
        assertThat(card(built).composedDreamCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from world_summaries where user_id=?", Long.class, user)).isZero();
        assertThat(generator.calls).hasValue(0);
    }

    @Test
    void listQueryCountStaysBoundedForMultipleWorldCardsIncludingJdbcReads() throws Exception {
        var a = ready(1); var b = ready(2);
        for (int i = 0; i < 6; i++) build(folder(a, b));
        var stats = emf.unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = stats.isStatisticsEnabled();
        stats.setStatisticsEnabled(true);
        try {
            stats.clear(); org.mockito.Mockito.clearInvocations(jdbc);
            assertThat(folders.list(user, null, 1, null).items()).hasSize(1);
            long singleJpa = stats.getPrepareStatementCount();
            long singleJdbc = jdbcQueries();
            stats.clear(); org.mockito.Mockito.clearInvocations(jdbc);
            assertThat(folders.list(user, null, 50, null).items()).hasSize(6);
            assertThat(stats.getPrepareStatementCount()).isEqualTo(singleJpa).isEqualTo(3);
            assertThat(jdbcQueries()).isEqualTo(singleJdbc).isPositive();
            assertThat(generator.calls).hasValue(0);
        } finally {
            stats.setStatisticsEnabled(wasEnabled);
        }
    }

    private long jdbcQueries() {
        return org.mockito.Mockito.mockingDetails(jdbc).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("query")).count();
    }

    @Test
    void publishesFreeSummaryFromFixedCompositionWithoutRenamingFolderOrCharging()
            throws Exception {
        var a = ready(1);
        var b = ready(2);
        var world = folder(b, a);
        build(world);
        assertThat(summaries.get(user, world).status())
                .isEqualTo(WorldSummaryResponse.Status.PENDING);
        assertThat(generator.calls).hasValue(0);
        var done = pump(world);
        assertThat(done.status()).isEqualTo(WorldSummaryResponse.Status.COMPLETED);
        assertThat(done.generatedTitle()).isEqualTo("하늘을 건넌 바다");
        assertThat(done.sourceCount()).isEqualTo(2);
        assertThat(done.commonKeywords())
                .extracting(WorldSummaryContent.Keyword::keyword)
                .containsExactly("바다", "하늘");
        assertThat(done.recurringEmotions())
                .extracting(WorldSummaryContent.Emotion::code)
                .containsExactly(DreamEmotion.HAPPY);
        assertThat(done.refreshNeeded()).isFalse();
        assertThat(done.limitedEvidence()).isFalse();
        assertThat(generator.input.data().stories().get(1).connectionBefore()).contains("다음 바다");
        assertThat(folders.get(user, world).name()).isEqualTo("첫 꿈 제목 외 N개");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from credit_ledger where user_id=?",
                                Long.class,
                                user))
                .isZero();
        for (int i = 0; i < 3; i++)
            assertThat(summaries.request(user, world).generatedTitle())
                    .isEqualTo(done.generatedTitle());
        assertThat(generator.calls).hasValue(1);
    }

    @Test
    void unchangedResultStillReadableAndReusableWhenDisabledOrSourceEdited() throws Exception {
        var a = ready(1);
        var world = folder(a, ready(2));
        build(world);
        pump(world);
        edit(a);
        org.mockito.Mockito.doReturn(false).when(properties).enabled();
        generator.enabled = false;
        var response = summaries.request(user, world);
        assertThat(response.sourceChanged()).isTrue();
        assertThat(response.refreshNeeded()).isTrue();
        assertThat(response.generatedTitle()).isEqualTo("하늘을 건넌 바다");
        assertThat(response.recurringEmotions().getFirst().code()).isEqualTo(DreamEmotion.HAPPY);
        assertThat(generator.calls).hasValue(1);
    }

    @Test
    void firstTitleMayUseOldPublishedSnapshotAndMustNotUseCurrentEmotions() throws Exception {
        var a = ready(1);
        var world = folder(a, ready(2));
        build(world);
        edit(a);
        var done = pump(world);
        assertThat(done.status()).isEqualTo(WorldSummaryResponse.Status.COMPLETED);
        assertThat(done.recurringEmotions())
                .extracting(WorldSummaryContent.Emotion::code)
                .containsOnly(DreamEmotion.HAPPY);
        assertThat(done.sourceChanged()).isTrue();
        assertThat(done.refreshNeeded()).isTrue();
    }

    @Test
    void rebuildingIdenticalPublishedInputsReusesResultAcrossCompositionRevisions()
            throws Exception {
        var world = folder(ready(1), ready(2));
        build(world);
        var first = pump(world);
        build(world);
        var reused = pump(world);
        assertThat(reused.status()).isEqualTo(WorldSummaryResponse.Status.COMPLETED);
        assertThat(reused.resultCompositionRevision()).isEqualTo(2);
        assertThat(reused.generatedAt()).isEqualTo(first.generatedAt());
        assertThat(reused.refreshNeeded()).isFalse();
        assertThat(generator.calls).hasValue(1);
    }

    @Test
    void selectionChangesAloneDoNotAlterPublishedSummary() throws Exception {
        var a = ready(1);
        var b = ready(2);
        var world = folder(a, b);
        build(world);
        var before = pump(world);
        folders.exclude(user, world, b.dreamId(), folders.get(user, world).revision());
        var after = summaries.get(user, world);
        assertThat(after.generatedAt()).isEqualTo(before.generatedAt());
        assertThat(after.sourceCount()).isEqualTo(2);
        assertThat(after.refreshNeeded()).isFalse();
    }

    @Test
    void legacyMissingSnapshotsProduceLimitedEvidenceWithoutMutableFallback() throws Exception {
        var a = ready(1);
        var world = folder(a, ready(2));
        build(world);
        jdbc.update(
                "update dream_story_versions set analysis_json=null,source_emotions=null where"
                        + " id=?",
                version(a.dreamId()));
        var done = pump(world);
        assertThat(done.status()).isEqualTo(WorldSummaryResponse.Status.COMPLETED);
        assertThat(done.limitedEvidence()).isTrue();
        assertThat(done.commonKeywords()).isEmpty();
        assertThat(done.recurringEmotions()).isEmpty();
    }

    @Test
    void failureRetainsPreviousResultAndExplicitRetrySucceeds() throws Exception {
        var world = folder(ready(1), ready(2));
        build(world);
        var first = pump(world);
        folders.select(user, world, ready(3).dreamId(), folders.get(user, world).revision());
        build(world);
        generator.output = "bad";
        var failed = pump(world);
        assertThat(failed.status()).isEqualTo(WorldSummaryResponse.Status.FAILED);
        assertThat(failed.failureCode()).isEqualTo("INVALID_OUTPUT");
        assertThat(failed.generatedTitle()).isEqualTo(first.generatedTitle());
        assertThat(failed.hasPreviousResult()).isTrue();
        assertThat(failed.refreshNeeded()).isTrue();
        generator.output = "{\"generatedTitle\":\"세 번째 바다\"}";
        summaries.request(user, world);
        var done = pump(world);
        assertThat(done.generatedTitle()).isEqualTo("세 번째 바다");
        assertThat(done.sourceCount()).isEqualTo(3);
        assertThat(done.resultCompositionRevision()).isEqualTo(2);
        assertThat(generator.calls).hasValue(3);
    }

    @Test
    void pendingAndConcurrentRequestsReuseSingleAttempt() throws Exception {
        var world = folder(ready(1), ready(2));
        build(world);
        generator.pending = new CompletableFuture<>();
        try (var executor = Executors.newFixedThreadPool(6)) {
            var requests = new ArrayList<Future<WorldSummaryResponse>>();
            for (int i = 0; i < 6; i++)
                requests.add(executor.submit(() -> summaries.request(user, world)));
            for (var request : requests)
                assertThat(request.get(5, TimeUnit.SECONDS).status())
                        .isEqualTo(WorldSummaryResponse.Status.PENDING);
        }
        summaryWorker.poll();
        summaryWorker.poll();
        assertThat(generator.calls).hasValue(1);
        assertThat(summaries.request(user, world).status())
                .isEqualTo(WorldSummaryResponse.Status.PROCESSING);
        generator.pending.complete(generator.output);
        assertThat(pump(world).status()).isEqualTo(WorldSummaryResponse.Status.COMPLETED);
    }

    @Test
    void editsDuringCallRejectLateOutput() throws Exception {
        var a = ready(1);
        var world = folder(a, ready(2));
        build(world);
        generator.pending = new CompletableFuture<>();
        summaryWorker.poll();
        edit(a);
        generator.pending.complete(generator.output);
        var failed = pump(world);
        assertThat(failed.status()).isEqualTo(WorldSummaryResponse.Status.FAILED);
        assertThat(failed.failureCode()).isEqualTo("INPUT_CHANGED");
        assertThat(failed.generatedTitle()).isNull();
    }

    @Test
    void deletedSourceClearsTextAndLateResponseCannotRestoreIt() throws Exception {
        var a = ready(1);
        var b = ready(2);
        var world = folder(a, b);
        build(world);
        generator.pending = new CompletableFuture<>();
        summaryWorker.poll();
        dreams.delete(user, b.dreamId(), b.revision());
        generator.pending.complete(generator.output);
        var failed = pump(world);
        assertThat(failed.failureCode()).isEqualTo("SOURCE_DELETED");
        assertThat(failed.generatedTitle()).isNull();
        generator.pending = null;
        summaries.request(user, world);
        var one = pump(world);
        assertThat(one.sourceCount()).isEqualTo(1);
        assertThat(one.commonKeywords()).isEmpty();
        assertThat(one.recurringEmotions()).isEmpty();
        dreams.delete(user, a.dreamId(), a.revision());
        assertThat(summaries.get(user, world).status())
                .isEqualTo(WorldSummaryResponse.Status.EMPTY);
        assertThat(summaries.request(user, world).generatedTitle()).isNull();
        assertThat(generator.calls).hasValue(2);
    }

    @Test
    void oldResultReferencingExcludedDreamIsRemovedButNewValidAttemptCanFinish() throws Exception {
        var a = ready(1);
        var b = ready(2);
        var world = folder(a, b);
        build(world);
        pump(world);
        folders.exclude(user, world, b.dreamId(), folders.get(user, world).revision());
        folders.select(user, world, ready(3).dreamId(), folders.get(user, world).revision());
        build(world);
        generator.pending = new CompletableFuture<>();
        summaryWorker.poll();
        dreams.delete(user, b.dreamId(), b.revision());
        var pending = summaries.get(user, world);
        assertThat(pending.generatedTitle()).isNull();
        assertThat(pending.status()).isEqualTo(WorldSummaryResponse.Status.PROCESSING);
        generator.pending.complete(generator.output);
        assertThat(pump(world).status()).isEqualTo(WorldSummaryResponse.Status.COMPLETED);
    }

    @Test
    void folderDeletionRemovesRowsAndLateCompletionDoesNotResurrect() throws Exception {
        var world = folder(ready(1), ready(2));
        build(world);
        generator.pending = new CompletableFuture<>();
        summaryWorker.poll();
        folders.delete(user, world, folders.get(user, world).revision());
        generator.pending.complete(generator.output);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (summaryWorker.activeCount() > 0 && System.nanoTime() < end) Thread.sleep(5);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from world_summaries where world_id=?",
                                Long.class,
                                world))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from world_summary_sources where world_id=?",
                                Long.class,
                                world))
                .isZero();
    }

    @Test
    void leaseExpirationDoesNotAutomaticallyResendAndOldAttemptCannotOverwriteRetry()
            throws Exception {
        var world = folder(ready(1), ready(2));
        build(world);
        generator.pending = new CompletableFuture<>();
        var old = generator.pending;
        summaryWorker.poll();
        clock.now = clock.now.plusSeconds(121);
        summaryWorker.poll();
        var expired = summaries.get(user, world);
        assertThat(expired.status()).isEqualTo(WorldSummaryResponse.Status.FAILED);
        assertThat(expired.failureCode()).isEqualTo("ATTEMPT_EXPIRED");
        assertThat(generator.calls).hasValue(1);
        generator.pending = null;
        generator.output = "{\"generatedTitle\":\"다시 찾은 바다\"}";
        summaries.request(user, world);
        assertThat(pump(world).generatedTitle()).isEqualTo("다시 찾은 바다");
        old.complete("{\"generatedTitle\":\"오래된 제목\"}");
        Thread.sleep(30);
        assertThat(summaries.get(user, world).generatedTitle()).isEqualTo("다시 찾은 바다");
    }

    @Test
    void partialApplicationQueuesNewCompositionAndInvalidatesInFlightResult() throws Exception {
        var a = ready(1);
        var world = folder(a, ready(2));
        build(world);
        generator.pending = new CompletableFuture<>();
        summaryWorker.poll();
        var latest = storyTransactions.latest(user, a.dreamId());
        await(
                stories.generate(
                        user,
                        a.dreamId(),
                        new StoryRequest(
                                dreams.get(user, a.dreamId()).revision(),
                                true,
                                latest.storyVersion())));
        worlds.apply(
                user,
                world,
                a.dreamId(),
                new WorldApplyRequest(
                        folders.get(user, world).revision(),
                        worlds.get(user, world).compositionRevision(),
                        version(a.dreamId())));
        pumpWorld(world);
        generator.pending.complete(generator.output);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (summaryWorker.activeCount() > 0 && System.nanoTime() < end) Thread.sleep(5);
        assertThat(summaries.get(user, world).status())
                .isEqualTo(WorldSummaryResponse.Status.PENDING);
        generator.pending = null;
        var done = pump(world);
        assertThat(done.resultCompositionRevision()).isEqualTo(2);
        assertThat(done.recurringEmotions())
                .extracting(WorldSummaryContent.Emotion::code)
                .containsExactly(DreamEmotion.HAPPY);
        assertThat(generator.calls).hasValue(2);
    }

    @Test
    void disabledUnavailableAndOversizedInputMakeNoExternalCall() throws Exception {
        org.mockito.Mockito.doReturn(false).when(properties).enabled();
        var world = folder(ready(1), ready(2));
        build(world);
        assertThat(summaries.get(user, world).status())
                .isEqualTo(WorldSummaryResponse.Status.NOT_GENERATED);
        assertThatThrownBy(() -> summaries.request(user, world))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(WorldSummaryErrorCode.UNAVAILABLE);
        org.mockito.Mockito.doReturn(true).when(properties).enabled();
        generator.enabled = false;
        assertThatThrownBy(() -> summaries.request(user, world))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(WorldSummaryErrorCode.UNAVAILABLE);
        generator.enabled = true;
        org.mockito.Mockito.doReturn(1000).when(properties).maxInputCharacters();
        jdbc.update(
                "update dream_story_versions set result_json=? where id=?",
                "x".repeat(1100),
                version(worlds.page(user, world, null, 50).items().getFirst().dreamId()));
        assertThatThrownBy(() -> summaries.request(user, world))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(WorldSummaryErrorCode.TOO_LARGE);
        assertThat(generator.calls).hasValue(0);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from world_summaries where world_id=?",
                                Long.class,
                                world))
                .isZero();
    }

    @Test
    void missingCompositionAndOwnershipAreCheckedBeforeGeneration() {
        var world = folders.create(user, "빈 폴더").worldId();
        assertThat(summaries.get(user, world).status())
                .isEqualTo(WorldSummaryResponse.Status.NOT_GENERATED);
        assertThatThrownBy(() -> summaries.request(user, world))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(WorldSummaryErrorCode.REQUIRED);
        Long other =
                users.saveAndFlush(User.create(UUID.randomUUID() + "@test.invalid", "다른회원"))
                        .getId();
        assertThatThrownBy(() -> summaries.get(other, world))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(WorldFolderErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> summaries.request(other, world))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(WorldFolderErrorCode.NOT_FOUND);
        assertThat(generator.calls).hasValue(0);
    }

    @Test
    void failedResultPersistenceRollsBackBothResultAndSourceReplacement() throws Exception {
        var world = folder(ready(1), ready(2));
        build(world);
        var first = pump(world);
        var before =
                jdbc.queryForList(
                        "select dream_id,story_version_id from world_summary_sources where"
                                + " world_id=? and candidate=false order by dream_id",
                        world);
        folders.select(user, world, ready(3).dreamId(), folders.get(user, world).revision());
        build(world);
        var step = summaries.claim(user, world);
        assertThat(step).isNotNull();
        org.mockito.Mockito.doThrow(new IllegalStateException("simulated persistence failure"))
                .when(summaryRepository)
                .flush();
        try {
            assertThatThrownBy(
                            () -> summaries.complete(step, "{\"generatedTitle\":\"새 제목\"}", null))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            org.mockito.Mockito.reset(summaryRepository);
        }
        var still = summaries.get(user, world);
        assertThat(still.status()).isEqualTo(WorldSummaryResponse.Status.PROCESSING);
        assertThat(still.generatedTitle()).isEqualTo(first.generatedTitle());
        assertThat(
                        jdbc.queryForList(
                                "select dream_id,story_version_id from world_summary_sources where"
                                        + " world_id=? and candidate=false order by dream_id",
                                world))
                .isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from world_summary_sources where world_id=? and"
                                        + " candidate=true",
                                Long.class,
                                world))
                .isEqualTo(3);
        clock.now = clock.now.plusSeconds(121);
        assertThat(summaries.get(user, world).failureCode()).isEqualTo("ATTEMPT_EXPIRED");
    }

    @Test
    void rejectsForeignStoryVersionEvenIfCompositionReferenceWasCorrupted() throws Exception {
        var a = ready(1);
        var world = folder(a, ready(2));
        build(world);
        Long owner = user;
        user = users.saveAndFlush(User.create(UUID.randomUUID() + "@test.invalid", "다른출처")).getId();
        var foreign = ready(3);
        long foreignVersion = version(foreign.dreamId());
        user = owner;
        jdbc.update(
                "update world_composition_entries set story_version_id=? where world_id=? and"
                        + " dream_id=? and candidate=false",
                foreignVersion,
                world,
                a.dreamId());
        var failed = pump(world);
        assertThat(failed.failureCode()).isEqualTo("INPUT_UNAVAILABLE");
        assertThat(failed.generatedTitle()).isNull();
        assertThat(generator.calls).hasValue(0);
    }

    @Test
    void unavailableProviderFailsQueuedJobOnceWithoutAutomaticResend() throws Exception {
        var world = folder(ready(1), ready(2));
        build(world);
        generator.enabled = false;
        var failed = pump(world);
        assertThat(failed.failureCode()).isEqualTo("UNAVAILABLE");
        assertThat(generator.calls).hasValue(0);
        generator.enabled = true;
        summaryWorker.poll();
        assertThat(summaries.get(user, world).status())
                .isEqualTo(WorldSummaryResponse.Status.FAILED);
        summaries.request(user, world);
        assertThat(pump(world).status()).isEqualTo(WorldSummaryResponse.Status.COMPLETED);
        assertThat(generator.calls).hasValue(1);
    }

    @Test
    void httpStatusNoStoreAuthenticationAndOpenApiContracts() throws Exception {
        var world = folder(ready(1), ready(2));
        build(world);
        var client = HttpClient.newHttpClient();
        String endpoint = "http://localhost:" + port + "/api/v1/worlds/" + world + "/summary";
        var unauthorized =
                client.send(
                        HttpRequest.newBuilder(URI.create(endpoint)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(unauthorized.statusCode()).isEqualTo(401);
        String token = tokens.login(user).response().accessToken();
        var accepted =
                client.send(
                        HttpRequest.newBuilder(URI.create(endpoint))
                                .header("Authorization", "Bearer " + token)
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(accepted.statusCode()).isEqualTo(202);
        assertThat(accepted.headers().firstValue("cache-control")).hasValue("no-store");
        pump(world);
        var done =
                client.send(
                        HttpRequest.newBuilder(URI.create(endpoint))
                                .header("Authorization", "Bearer " + token)
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(done.statusCode()).isEqualTo(200);
        var docs =
                client.send(
                        HttpRequest.newBuilder(
                                        URI.create("http://localhost:" + port + "/v3/api-docs"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        var contract =
                JsonMapper.builder()
                        .build()
                        .readTree(docs.body())
                        .path("paths")
                        .path("/api/v1/worlds/{worldId}/summary");
        assertThat(contract.has("get")).isTrue();
        assertThat(contract.path("post").path("responses").has("202")).isTrue();
        assertThat(done.body()).doesNotContain("바다 위를 날아갔다", "attemptSettings", "source_emotions");
    }
}
