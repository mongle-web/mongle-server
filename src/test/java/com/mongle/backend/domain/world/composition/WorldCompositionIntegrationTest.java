package com.mongle.backend.domain.world.composition;

import static com.mongle.backend.domain.dream.gateway.DreamAiTestAwait.await;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.auth.service.TokenService;
import com.mongle.backend.domain.dream.analysis.*;
import com.mongle.backend.domain.dream.dto.*;
import com.mongle.backend.domain.dream.entity.DreamEmotion;
import com.mongle.backend.domain.dream.generation.DreamGenerationGuard;
import com.mongle.backend.domain.dream.service.DreamService;
import com.mongle.backend.domain.dream.story.*;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.domain.world.bridge.*;
import com.mongle.backend.domain.world.folder.*;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.error.BusinessException;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:world-compositions;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
        })
@ActiveProfiles("test")
@Import(WorldCompositionIntegrationTest.Config.class)
class WorldCompositionIntegrationTest {
    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        StructureGenerator structure() {
            return input ->
                    CompletableFuture.completedFuture(
                            """
{"generatedTitle":"Ocean","displayKeywords":["ocean"],"elements":[],"scenes":[{"sequence":1,"content":"Saw ocean","disconnectedFromPrevious":false,"elementKeys":[]}]}
""");
        }

        @Bean
        @Primary
        TestStory story() {
            return new TestStory();
        }

        @Bean
        @Primary
        TestBridge bridge() {
            return new TestBridge();
        }
    }

    static class TestStory implements StoryGenerator {
        volatile String content = "Old story";

        public CompletableFuture<String> generate(Input input) {
            return CompletableFuture.completedFuture(
                    "{\"sections\":[{\"sequence\":1,\"kind\":\"SCENE\",\"sceneSequence\":1,\"content\":\""
                            + content
                            + "\"}]}");
        }
    }

    static class TestBridge implements BridgeGenerator {
        AtomicInteger calls = new AtomicInteger();
        volatile CompletableFuture<String> pending;
        volatile boolean fail;
        volatile boolean enabled = true;
        volatile int failAt = -1;
        List<Input> inputs = new CopyOnWriteArrayList<>();

        public boolean available() {
            return enabled;
        }

        public CompletableFuture<String> generate(Input input) {
            assertThat(
                            org.springframework.transaction.support
                                    .TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            inputs.add(input);
            int call = calls.incrementAndGet();
            return pending != null
                    ? pending
                    : CompletableFuture.completedFuture(
                            fail || call == failAt ? "bad" : "{\"content\":\"Across the ocean\"}");
        }
    }

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    WorldCompositionProperties properties;

    @Autowired WorldCompositionTransactions worlds;
    @Autowired WorldCompositionWorker worker;
    @Autowired WorldImpactService impacts;
    @Autowired WorldFolderService folders;
    @Autowired DreamService dreams;
    @Autowired DreamStructureService analyses;
    @Autowired DreamStoryService stories;
    @Autowired StoryTransactions storyTransactions;
    @Autowired DreamGenerationGuard guard;
    @Autowired UserRepository users;
    @Autowired TokenService tokens;
    @Autowired TestBridge bridge;
    @Autowired TestStory story;
    @Autowired JdbcTemplate jdbc;

    @Value("${local.server.port}")
    int port;

    Long user;

    @BeforeEach
    void setup() {
        user =
                users.saveAndFlush(User.create(UUID.randomUUID() + "@test.com", "WorldTest"))
                        .getId();
        bridge.calls.set(0);
        bridge.pending = null;
        bridge.fail = false;
        bridge.failAt = -1;
        bridge.enabled = true;
        bridge.inputs.clear();
        story.content = "Old story";
    }

    @AfterEach
    void release() {
        jdbc.update(
                "update world_compositions set status='FAILED',claim_token=null,claim_until=null"
                        + " where user_id=? and status in ('PENDING','PROCESSING')",
                user);
        if (bridge.pending != null) bridge.pending.complete("{\"content\":\"Late bridge\"}");
    }

    DreamResponse ready(int day) {
        var d =
                dreams.create(
                        user, new DreamCreateRequest(LocalDate.of(2026, 10, day), "Saw ocean"));
        d =
                dreams.complete(
                        user,
                        d.dreamId(),
                        new DreamEmotionsRequest(d.revision(), List.of(DreamEmotion.HAPPY)));
        await(analyses.analyze(user, d.dreamId(), d.revision()));
        d = dreams.get(user, d.dreamId());
        await(stories.generate(user, d.dreamId(), new StoryRequest(d.revision(), false, null)));
        return d;
    }

    WorldFolderResponse folder(DreamResponse... sources) {
        var f = folders.create(user, "Ocean");
        for (var d : sources) f = folders.select(user, f.worldId(), d.dreamId(), f.revision());
        return f;
    }

    WorldCompositionResponse build(WorldFolderResponse f) {
        return worlds.build(
                user,
                f.worldId(),
                new WorldBuildRequest(
                        f.revision(), worlds.get(user, f.worldId()).compositionRevision()));
    }

    WorldCompositionResponse pump(Long world) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            worker.poll();
            var state = worlds.get(user, world);
            if (state.status() == GenerationStatus.COMPLETED
                    || state.status() == GenerationStatus.FAILED) return state;
            Thread.sleep(5);
        }
        throw new AssertionError("World job did not finish");
    }

    @Test
    void freezesDateOrderedStoriesAndReusesBridgesAcrossFoldersWithoutCharging() throws Exception {
        var a = ready(1);
        var b = ready(2);
        var c = ready(3);
        var f = folder(c, a, b);
        build(f);
        var done = pump(f.worldId());
        assertThat(done.status()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(done.compositionRevision()).isEqualTo(1);
        assertThat(done.hasResult()).isTrue();
        assertThat(done.selectionChanged()).isFalse();
        var entries = worlds.page(user, f.worldId(), null, 50).items();
        assertThat(entries)
                .extracting(WorldEntryPage.Entry::dreamId)
                .containsExactly(a.dreamId(), b.dreamId(), c.dreamId());
        assertThat(entries.get(1).bridgeBefore()).isEqualTo("Across the ocean");
        assertThat(bridge.calls).hasValue(2);
        var other = folder(b, c, a);
        build(other);
        assertThat(pump(other.worldId()).status()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(bridge.calls).hasValue(2);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from credit_ledger where user_id=?",
                                Long.class,
                                user))
                .isZero();
    }

    @Test
    void duplicatePendingRequestsReuseAndBlockSourceMutation() {
        var a = ready(1);
        var b = ready(2);
        var f = folder(a, b);
        var first = build(f);
        assertThat(build(f).attemptId()).isEqualTo(first.attemptId());
        assertThat(guard.status(user, a.dreamId()).mutable()).isFalse();
        assertThat(guard.status(user, a.dreamId()).blockingTasks())
                .contains(DreamMutationStatus.BlockingTask.WORLD_COMPOSITION);
        var edit = new DreamUpdateRequest();
        edit.setRevision(a.revision());
        edit.setTitle("Changed");
        assertThatThrownBy(() -> dreams.update(user, a.dreamId(), edit))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> dreams.delete(user, a.dreamId(), a.revision()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                stories.generate(
                                        user,
                                        a.dreamId(),
                                        new StoryRequest(
                                                a.revision(),
                                                true,
                                                storyTransactions
                                                        .latest(user, a.dreamId())
                                                        .storyVersion())))
                .isInstanceOf(BusinessException.class);
        assertThat(bridge.calls).hasValue(0);
    }

    @Test
    void failedReplacementKeepsPreviousResultAndExplicitRetryCompletes() throws Exception {
        var a = ready(1);
        var b = ready(2);
        var f = folder(a, b);
        build(f);
        pump(f.worldId());
        var before = worlds.page(user, f.worldId(), null, 50);
        story.content = "New story";
        var old = storyTransactions.latest(user, b.dreamId());
        await(
                stories.generate(
                        user,
                        b.dreamId(),
                        new StoryRequest(b.revision(), true, old.storyVersion())));
        bridge.fail = true;
        build(f);
        assertThat(pump(f.worldId()).status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(worlds.page(user, f.worldId(), null, 50).items())
                .extracting(WorldEntryPage.Entry::storyVersionId)
                .containsExactlyElementsOf(
                        before.items().stream().map(WorldEntryPage.Entry::storyVersionId).toList());
        var failed = worlds.get(user, f.worldId());
        bridge.fail = false;
        worlds.retry(user, f.worldId(), failed.jobVersion());
        assertThat(pump(f.worldId()).status()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(worlds.get(user, f.worldId()).compositionRevision()).isEqualTo(2);
    }

    @Test
    void missingCurrentStoriesAndSmallSelectionAreRejectedBeforeAi() {
        var a = ready(1);
        var f = folder(a);
        var small = f;
        assertThatThrownBy(() -> build(small))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(WorldCompositionErrorCode.SOURCES_REQUIRED);
        var b = ready(2);
        f = folders.select(user, f.worldId(), b.dreamId(), f.revision());
        var edit = new DreamUpdateRequest();
        edit.setRevision(b.revision());
        edit.setOriginalText("Changed source");
        dreams.update(user, b.dreamId(), edit);
        var current = f;
        assertThatThrownBy(() -> build(current))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(WorldCompositionErrorCode.SOURCE_NOT_READY);
        assertThat(bridge.calls).hasValue(0);
    }

    @Test
    void folderChangeAndExpiredClaimCannotPublishLateResult() throws Exception {
        var a = ready(1);
        var b = ready(2);
        var f = folder(a, b);
        bridge.pending = new CompletableFuture<>();
        build(f);
        worker.poll();
        folders.rename(user, f.worldId(), new WorldFolderUpdateRequest("Changed", f.revision()));
        bridge.pending.complete("{\"content\":\"Across the ocean\"}");
        assertThat(pump(f.worldId()).status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(worlds.get(user, f.worldId()).hasResult()).isFalse();
        var newFolder = folders.get(user, f.worldId());
        bridge.pending = null;
        build(newFolder);
        var step = worlds.next(user, f.worldId());
        jdbc.update(
                "update world_compositions set claim_until=? where world_id=?",
                java.sql.Timestamp.from(Instant.EPOCH),
                f.worldId());
        worker.poll();
        assertThat(worlds.get(user, f.worldId()).failureCode()).isEqualTo("WORKER_INTERRUPTED");
        assertThat(step).isNotNull();
        assertThat(guard.status(user, a.dreamId()).mutable()).isTrue();
    }

    @Test
    void pageCursorChangesOnlyWithCompletedCompositionAndForeignOwnershipIsHidden()
            throws Exception {
        var f = folder(ready(1), ready(2), ready(3));
        build(f);
        pump(f.worldId());
        var page = worlds.page(user, f.worldId(), null, 1);
        assertThat(page.hasNext()).isTrue();
        assertThat(worlds.page(user, f.worldId(), page.nextCursor(), 1).items()).hasSize(1);
        folders.rename(user, f.worldId(), new WorldFolderUpdateRequest("Renamed", f.revision()));
        assertThat(worlds.page(user, f.worldId(), page.nextCursor(), 1).items()).hasSize(1);
        var other =
                users.saveAndFlush(User.create(UUID.randomUUID() + "@test.com", "Other")).getId();
        assertThatThrownBy(() -> worlds.get(other, f.worldId()))
                .isInstanceOf(BusinessException.class);
        var fresh = folders.get(user, f.worldId());
        build(fresh);
        pump(f.worldId());
        assertThatThrownBy(() -> worlds.page(user, f.worldId(), page.nextCursor(), 1))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(WorldCompositionErrorCode.CONFLICT);
    }

    Long regenerate(DreamResponse dream, String content) {
        story.content = content;
        var latest = storyTransactions.latest(user, dream.dreamId());
        return await(
                        stories.generate(
                                user,
                                dream.dreamId(),
                                new StoryRequest(dream.revision(), true, latest.storyVersion())))
                .resultVersionId();
    }

    @Test
    void partialApplyReplacesOnlyTargetAndAdjacentBridges() throws Exception {
        var a = ready(1);
        var b = ready(2);
        var c = ready(3);
        var d = ready(4);
        var f = folder(d, c, b, a);
        build(f);
        pump(f.worldId());
        var before = worlds.page(user, f.worldId(), null, 50).items();
        var bNew = regenerate(b, "New B");
        regenerate(c, "New C");
        assertThat(worlds.page(user, f.worldId(), null, 50).items().get(1).storyVersionId())
                .isEqualTo(before.get(1).storyVersionId());
        var request = new WorldApplyRequest(f.revision(), 1L, bNew);
        var running = worlds.apply(user, f.worldId(), b.dreamId(), request);
        assertThat(worlds.apply(user, f.worldId(), b.dreamId(), request).attemptId())
                .isEqualTo(running.attemptId());
        assertThat(pump(f.worldId()).status()).isEqualTo(GenerationStatus.COMPLETED);
        var after = worlds.page(user, f.worldId(), null, 50).items();
        assertThat(after.get(0).storyVersionId()).isEqualTo(before.get(0).storyVersionId());
        assertThat(after.get(1).storyVersionId()).isEqualTo(bNew);
        assertThat(after.get(2).storyVersionId()).isEqualTo(before.get(2).storyVersionId());
        assertThat(after.get(2).newStoryAvailable()).isTrue();
        assertThat(worlds.get(user, f.worldId()).newStoryAvailable()).isTrue();
        assertThat(after.get(3).bridgeBeforeId()).isEqualTo(before.get(3).bridgeBeforeId());
        assertThat(bridge.calls).hasValue(5);
        assertThat(bridge.inputs.get(3).afterStory()).isEqualTo("New B");
        assertThat(bridge.inputs.get(4).beforeStory()).isEqualTo("New B");
        assertThat(bridge.inputs.get(4).afterStory()).isEqualTo("Old story");
    }

    @Test
    void partialApplyFailurePreservesAllOriginalVersionsAndBridges() throws Exception {
        var a = ready(1);
        var b = ready(2);
        var c = ready(3);
        var f = folder(a, b, c);
        build(f);
        pump(f.worldId());
        var before = worlds.page(user, f.worldId(), null, 50).items();
        var version = regenerate(b, "New B");
        bridge.failAt = 4;
        worlds.apply(
                user, f.worldId(), b.dreamId(), new WorldApplyRequest(f.revision(), 1L, version));
        assertThat(pump(f.worldId()).status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(worlds.get(user, f.worldId()).compositionRevision()).isEqualTo(1);
        var after = worlds.page(user, f.worldId(), null, 50).items();
        assertThat(after)
                .extracting(WorldEntryPage.Entry::storyVersionId)
                .containsExactlyElementsOf(
                        before.stream().map(WorldEntryPage.Entry::storyVersionId).toList());
        assertThat(after)
                .extracting(WorldEntryPage.Entry::bridgeBeforeId)
                .containsExactlyElementsOf(
                        before.stream().map(WorldEntryPage.Entry::bridgeBeforeId).toList());
        bridge.failAt = -1;
        worlds.retry(user, f.worldId(), worlds.get(user, f.worldId()).jobVersion());
        assertThat(pump(f.worldId()).status()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(bridge.calls).hasValue(5);
    }

    @Test
    void noOpApplyDoesNotCallAiAndStaleCompositionCannotOverwrite() throws Exception {
        var a = ready(1);
        var b = ready(2);
        var f = folder(a, b);
        build(f);
        pump(f.worldId());
        var fixed = worlds.page(user, f.worldId(), null, 50).items().getFirst().storyVersionId();
        assertThat(
                        worlds.apply(
                                        user,
                                        f.worldId(),
                                        a.dreamId(),
                                        new WorldApplyRequest(f.revision(), 1L, fixed))
                                .compositionRevision())
                .isEqualTo(1);
        assertThat(bridge.calls).hasValue(1);
        assertThatThrownBy(
                        () ->
                                worlds.apply(
                                        user,
                                        f.worldId(),
                                        a.dreamId(),
                                        new WorldApplyRequest(f.revision(), 0L, fixed)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(WorldCompositionErrorCode.CONFLICT);
    }

    @Test
    void deletionUsesPublishedSourcesEvenAfterSelectionExclusionAndLeavesGap() throws Exception {
        var a = ready(1);
        var b = ready(2);
        var c = ready(3);
        var f = folder(a, b, c);
        build(f);
        pump(f.worldId());
        var excluded = folders.exclude(user, f.worldId(), b.dreamId(), f.revision());
        var affected = impacts.impacts(user, b.dreamId(), null, 20).items();
        assertThat(affected).hasSize(1);
        assertThat(affected.getFirst().selected()).isFalse();
        assertThat(affected.getFirst().usedInComposition()).isTrue();
        var before = worlds.page(user, f.worldId(), null, 1);
        dreams.delete(user, b.dreamId(), b.revision());
        var remaining = worlds.page(user, f.worldId(), null, 50).items();
        assertThat(remaining)
                .extracting(WorldEntryPage.Entry::dreamId)
                .containsExactly(a.dreamId(), c.dreamId());
        assertThat(remaining.getFirst().connectionGap()).isFalse();
        assertThat(remaining.get(1).connectionGap()).isTrue();
        assertThat(remaining.get(1).bridgeBeforeId()).isNull();
        assertThat(worlds.get(user, f.worldId()).hasConnectionGaps()).isTrue();
        assertThat(bridge.calls).hasValue(2);
        assertThat(folders.get(user, f.worldId()).selectedDreamCount()).isEqualTo(2);
        assertThatThrownBy(() -> worlds.page(user, f.worldId(), before.nextCursor(), 1))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(WorldCompositionErrorCode.CONFLICT);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from world_composition_entries where dream_id=?",
                                Long.class,
                                b.dreamId()))
                .isZero();
    }

    @Test
    void deletingToOneThenZeroKeepsFolderAndCompletedComposition() throws Exception {
        var a = ready(1);
        var b = ready(2);
        var f = folder(a, b);
        build(f);
        pump(f.worldId());
        dreams.delete(user, a.dreamId(), a.revision());
        var one = worlds.page(user, f.worldId(), null, 50).items();
        assertThat(one).hasSize(1);
        assertThat(one.getFirst().connectionGap()).isFalse();
        assertThat(worlds.get(user, f.worldId()).hasConnectionGaps()).isFalse();
        assertThat(worlds.get(user, f.worldId()).hasResult()).isTrue();
        dreams.delete(user, b.dreamId(), b.revision());
        assertThat(folders.get(user, f.worldId()).selectedDreamCount()).isZero();
        assertThat(worlds.get(user, f.worldId()).hasResult()).isTrue();
        assertThat(worlds.page(user, f.worldId(), null, 50).items()).isEmpty();
        assertThatThrownBy(() -> build(folders.get(user, f.worldId())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(WorldCompositionErrorCode.SOURCES_REQUIRED);
    }

    @Test
    void deletionRollsBackCompositionChangesIfDerivedDeletionFails() throws Exception {
        var a = ready(1);
        var b = ready(2);
        var c = ready(3);
        var f = folder(a, b, c);
        build(f);
        pump(f.worldId());
        var before = worlds.page(user, f.worldId(), null, 50);
        jdbc.execute(
                "create table world_delete_blocker(version_id BIGINT references"
                        + " dream_story_versions(id))");
        jdbc.update(
                "insert into world_delete_blocker values(?)",
                before.items().get(1).storyVersionId());
        try {
            assertThatThrownBy(() -> dreams.delete(user, b.dreamId(), b.revision()))
                    .isInstanceOf(RuntimeException.class);
            assertThat(worlds.page(user, f.worldId(), null, 50)).isEqualTo(before);
            assertThat(folders.get(user, f.worldId()).selectedDreamCount()).isEqualTo(3);
        } finally {
            jdbc.execute("drop table world_delete_blocker");
        }
        dreams.delete(user, b.dreamId(), b.revision());
        assertThat(worlds.page(user, f.worldId(), null, 50).items()).hasSize(2);
    }

    @Test
    void folderDeletionRemovesCompositionAndRejectsLateCompletion() throws Exception {
        var a = ready(1);
        var b = ready(2);
        var f = folder(a, b);
        bridge.pending = new CompletableFuture<>();
        build(f);
        worker.poll();
        folders.delete(user, f.worldId(), f.revision());
        bridge.pending.complete("{\"content\":\"Across the ocean\"}");
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (worker.activeCount() > 0 && System.nanoTime() < end) Thread.sleep(5);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from world_compositions where world_id=?",
                                Long.class,
                                f.worldId()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from world_composition_entries where world_id=?",
                                Long.class,
                                f.worldId()))
                .isZero();
        assertThat(dreams.get(user, a.dreamId())).isNotNull();
        assertThat(dreams.get(user, b.dreamId())).isNotNull();
        assertThatThrownBy(() -> worlds.get(user, f.worldId()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void impactPaginationIncludesSelectedOnlyAndHidesForeignDream() throws Exception {
        var a = ready(1);
        var b = ready(2);
        var f = folder(a, b);
        build(f);
        pump(f.worldId());
        var only = folder(a);
        var page = impacts.impacts(user, a.dreamId(), null, 1);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.items().getFirst().worldId()).isEqualTo(only.worldId());
        assertThat(page.items().getFirst().usedInComposition()).isFalse();
        assertThat(page.items().getFirst().selected()).isTrue();
        assertThat(
                        impacts.impacts(user, a.dreamId(), page.nextBeforeId(), 1)
                                .items()
                                .getFirst()
                                .worldId())
                .isEqualTo(f.worldId());
        var other =
                users.saveAndFlush(User.create(UUID.randomUUID() + "@test.com", "Other")).getId();
        assertThatThrownBy(() -> impacts.impacts(other, a.dreamId(), null, 20))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void expiredJobDreamDeletionRemovesCandidatesAndLateBridgeCannotResurrect() throws Exception {
        var a = ready(1);
        var b = ready(2);
        var f = folder(a, b);
        bridge.pending = new CompletableFuture<>();
        build(f);
        worker.poll();
        jdbc.update(
                "update world_compositions set lease_until=? where world_id=?",
                java.sql.Timestamp.from(Instant.EPOCH),
                f.worldId());
        jdbc.update(
                "update world_bridge_results set lease_until=? where user_id=?",
                java.sql.Timestamp.from(Instant.EPOCH),
                user);
        dreams.delete(user, a.dreamId(), a.revision());
        bridge.pending.complete("{\"content\":\"Late result\"}");
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (worker.activeCount() > 0 && System.nanoTime() < end) Thread.sleep(5);
        assertThat(worlds.get(user, f.worldId()).hasResult()).isFalse();
        assertThat(worlds.get(user, f.worldId()).failureCode()).isEqualTo("SOURCE_DELETED");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from world_composition_entries where world_id=?",
                                Long.class,
                                f.worldId()))
                .isZero();
        assertThat(folders.get(user, f.worldId()).selectedDreamCount()).isEqualTo(1);
    }

    @Test
    void sourceEditShowsChangedFlagWithoutRewritingPublishedStory() throws Exception {
        var a = ready(1);
        var b = ready(2);
        var f = folder(a, b);
        build(f);
        pump(f.worldId());
        var before = worlds.page(user, f.worldId(), null, 50).items();
        var edit = new DreamUpdateRequest();
        edit.setRevision(b.revision());
        edit.setOriginalText("Changed original");
        dreams.update(user, b.dreamId(), edit);
        var after = worlds.page(user, f.worldId(), null, 50).items();
        assertThat(after.get(1).sourceChanged()).isTrue();
        assertThat(worlds.get(user, f.worldId()).sourceChanged()).isTrue();
        assertThat(after.get(1).sections()).isEqualTo(before.get(1).sections());
        assertThat(bridge.calls).hasValue(1);
    }

    @Test
    void publishFailureRollsBackEntireReplacementWithoutRepeatingAi() throws Exception {
        var a = ready(1);
        var b = ready(2);
        var f = folder(a, b);
        build(f);
        pump(f.worldId());
        var before = worlds.page(user, f.worldId(), null, 50).items();
        var next = regenerate(b, "New B");
        worlds.apply(user, f.worldId(), b.dreamId(), new WorldApplyRequest(f.revision(), 1L, next));
        worker.poll();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (worker.activeCount() > 0 && System.nanoTime() < end) Thread.sleep(5);
        jdbc.execute(
                "alter table world_composition_entries add constraint composition_publish_failure"
                        + " check(candidate=true or story_version_id<>"
                        + next
                        + ")");
        try {
            assertThatThrownBy(() -> worlds.next(user, f.worldId()))
                    .isInstanceOf(RuntimeException.class);
            assertThat(worlds.page(user, f.worldId(), null, 50).items())
                    .extracting(WorldEntryPage.Entry::storyVersionId)
                    .containsExactlyElementsOf(
                            before.stream().map(WorldEntryPage.Entry::storyVersionId).toList());
            assertThat(worlds.get(user, f.worldId()).compositionRevision()).isEqualTo(1);
        } finally {
            jdbc.execute(
                    "alter table world_composition_entries drop constraint"
                            + " composition_publish_failure");
        }
        assertThat(pump(f.worldId()).status()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(bridge.calls).hasValue(2);
    }

    @Test
    void twoInstancesCannotClaimSameWorldAndOldAttemptCannotFailNewJob() throws Exception {
        var f = folder(ready(1), ready(2));
        build(f);
        WorldCompositionTransactions.Step first;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> worlds.next(user, f.worldId()));
            var two = pool.submit(() -> worlds.next(user, f.worldId()));
            var a = one.get(5, TimeUnit.SECONDS);
            var b = two.get(5, TimeUnit.SECONDS);
            assertThat((a == null) ^ (b == null)).isTrue();
            first = a == null ? b : a;
        }
        jdbc.update(
                "update world_compositions set claim_until=? where world_id=?",
                java.sql.Timestamp.from(Instant.EPOCH),
                f.worldId());
        worlds.next(user, f.worldId());
        var fresh = build(f);
        worlds.completed(first, null, true);
        assertThat(worlds.get(user, f.worldId()).attemptId()).isEqualTo(fresh.attemptId());
        assertThat(worlds.get(user, f.worldId()).status()).isEqualTo(GenerationStatus.PENDING);
    }

    java.net.http.HttpResponse<String> http(String path, String method, String body, String token)
            throws Exception {
        var request =
                java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://localhost:" + port + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(
                method,
                body == null
                        ? java.net.http.HttpRequest.BodyPublishers.noBody()
                        : java.net.http.HttpRequest.BodyPublishers.ofString(body));
        return java.net.http.HttpClient.newHttpClient()
                .send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void httpAndSwaggerExposeAllSixOperationsWithAuthenticationAndSchemas() throws Exception {
        var a = ready(1);
        var b = ready(2);
        var f = folder(a, b);
        String token = tokens.login(user).response().accessToken();
        String path = "/api/v1/worlds/" + f.worldId() + "/composition";
        assertThat(http(path, "GET", null, null).statusCode()).isEqualTo(401);
        assertThat(http(path, "POST", "{}", token).statusCode()).isEqualTo(400);
        var accepted =
                http(
                        path,
                        "POST",
                        "{\"folderRevision\":" + f.revision() + ",\"compositionRevision\":0}",
                        token);
        assertThat(accepted.statusCode()).isEqualTo(202);
        assertThat(accepted.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(accepted.body())
                .contains("attemptId")
                .doesNotContain("settingsJson", "claimToken");
        pump(f.worldId());
        assertThat(http(path + "/entries", "GET", null, token).body()).contains("Old story");
        assertThat(
                        http("/api/v1/dreams/" + a.dreamId() + "/world-impacts", "GET", null, token)
                                .body())
                .contains("usedInComposition");
        var docs =
                new tools.jackson.databind.ObjectMapper()
                        .readTree(http("/v3/api-docs", "GET", null, null).body());
        var root = "/api/v1/worlds/{worldId}/composition";
        var operations =
                List.of(
                        docs.path("paths").path(root).path("post"),
                        docs.path("paths").path(root).path("get"),
                        docs.path("paths").path(root + "/entries").path("get"),
                        docs.path("paths").path(root + "/retry").path("post"),
                        docs.path("paths").path(root + "/dreams/{dreamId}/apply").path("post"),
                        docs.path("paths")
                                .path("/api/v1/dreams/{dreamId}/world-impacts")
                                .path("get"));
        for (var operation : operations) {
            assertThat(operation.path("security").toString()).contains("bearerAuth");
            var responses = operation.path("responses");
            var status = responses.has("202") ? "202" : "200";
            assertThat(
                            responses
                                    .path(status)
                                    .path("content")
                                    .path("application/json")
                                    .path("schema")
                                    .toString())
                    .contains("$ref");
        }
    }

    @Test
    void disablingNewRequestsDoesNotDiscardAcceptedWorkOrFreeReads() throws Exception {
        var a = ready(1);
        var b = ready(2);
        var f = folder(a, b);
        build(f);
        org.mockito.Mockito.doReturn(false).when(properties).enabled();
        assertThat(pump(f.worldId()).status()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(worlds.page(user, f.worldId(), null, 50).items()).hasSize(2);
        assertThatThrownBy(() -> build(f))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(WorldCompositionErrorCode.UNAVAILABLE);
        var version = worlds.page(user, f.worldId(), null, 1).items().getFirst().storyVersionId();
        assertThat(
                        worlds.apply(
                                        user,
                                        f.worldId(),
                                        a.dreamId(),
                                        new WorldApplyRequest(f.revision(), 1L, version))
                                .compositionRevision())
                .isEqualTo(1);
    }
}
