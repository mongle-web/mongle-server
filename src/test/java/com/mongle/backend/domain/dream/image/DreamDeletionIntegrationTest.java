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
import com.mongle.backend.domain.world.bridge.*;
import com.mongle.backend.global.error.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dream-deletion;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "mongle.image.styles[0]=test-style"})
@ActiveProfiles("test")
@Import(DreamImageIntegrationTest.Config.class)
class DreamDeletionIntegrationTest {
    @Autowired DreamService dreams;
    @Autowired DreamStructureService analyses;
    @Autowired AnalysisTransactions analysisTransactions;
    @Autowired DreamStoryService stories;
    @Autowired StoryTransactions storyTransactions;
    @Autowired DreamImageService images;
    @Autowired ImageTransactions imageTransactions;
    @Autowired ImageCleanupTransactions cleanupTransactions;
    @Autowired BridgeTransactions bridges;
    @Autowired com.mongle.backend.domain.world.folder.WorldFolderService folders;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired DreamImageIntegrationTest.FakeGenerator generator;
    @Autowired DreamImageIntegrationTest.FakeStore storage;
    Long user;
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    record Graph(DreamResponse dream, AnalysisResponse analysis, StoryResponse story, ImageResponse image) {}

    @BeforeEach
    void setup() {
        user = users.saveAndFlush(User.create(UUID.randomUUID()+"@test.com", "삭제검증")).getId();
        generator.action = input -> ImagePayloadTest.png();
        storage.failPut = false;
        storage.failDelete = false;
        storage.deleted.clear();
    }

    Graph ready(Long owner, LocalDate date) {
        var draft = dreams.create(owner, new DreamCreateRequest(date, "바다를 보았다"));
        var complete = dreams.complete(owner, draft.dreamId(),
                new DreamEmotionsRequest(draft.revision(), List.of(DreamEmotion.HAPPY)));
        var analysis = await(analyses.analyze(owner, complete.dreamId(), complete.revision()));
        var dream = dreams.get(owner, complete.dreamId());
        var story = await(stories.generate(owner, dream.dreamId(), new StoryRequest(dream.revision(), false, null)));
        var image = await(images.generate(owner, dream.dreamId(), new ImageRequest(dream.revision(), "test-style", null, false, null)));
        return new Graph(dream, analysis, story, image);
    }

    long version(Graph graph) {
        return jdbc.queryForObject("select max(id) from dream_story_versions where story_id=?", Long.class, graph.story.storyId());
    }
    long count(String table, String column, Long id) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + column + "=?", Long.class, id);
    }
    void missing(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getHttpStatus().value()).isEqualTo(404));
    }

    @Test
    void removesEntireGraphAllVersionsAndOnlyRelatedBridgesWhileRetainingAuditAndOtherDreams() {
        var a = ready(user, today.minusDays(2));
        var b = ready(user, today.minusDays(1));
        var c = ready(user, today);
        var other = users.saveAndFlush(User.create(UUID.randomUUID()+"@test.com", "다른소유자")).getId();
        var foreign = ready(other, today);
        var ab = bridges.begin(user, new BridgeRequest(version(a), version(b)), true);
        bridges.finish(ab.input(), "앞 연결");
        var bc = bridges.begin(user, new BridgeRequest(version(b), version(c)), true);
        bridges.finish(bc.input(), "뒤 연결");
        var ac = bridges.begin(user, new BridgeRequest(version(a), version(c)), true);
        bridges.finish(ac.input(), "기존 다른 연결");
        long oldVersion = version(b);
        await(stories.generate(user, b.dream.dreamId(), new StoryRequest(b.dream.revision(), true, b.story.storyVersion())));
        assertThat(count("dream_story_versions", "story_id", b.story.storyId())).isEqualTo(2);
        // 분석 없는 구버전 그래프와 분석에 연결된 요소/장면도 함께 제거한다.
        jdbc.update("insert into dream_entities(dream_id,analysis_id,user_id,entity_type,name,normalized_name,created_at,updated_at) values(?,?,?,'PLACE','바다','바다',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                b.dream.dreamId(), b.analysis.analysisId(), user);
        long element = jdbc.queryForObject("select id from dream_entities where analysis_id=?", Long.class, b.analysis.analysisId());
        long scene = jdbc.queryForObject("select id from dream_scenes where analysis_id=?", Long.class, b.analysis.analysisId());
        jdbc.update("insert into dream_scene_entities(dream_scene_id,dream_entity_id) values(?,?)", scene, element);
        jdbc.update("insert into ai_generation_logs(user_id,task_type,success,created_at) values(?,'DREAM_STRUCTURE',true,CURRENT_TIMESTAMP)", user);
        String key = imageTransactions.assetKey(user, b.image.imageId());
        dreams.delete(user, b.dream.dreamId(), b.dream.revision());
        for (var table : List.of("dreams", "dream_analyses", "dream_stories", "dream_images")) {
            long id = switch(table) { case "dreams" -> b.dream.dreamId(); case "dream_analyses" -> b.analysis.analysisId(); case "dream_stories" -> b.story.storyId(); default -> b.image.imageId(); };
            assertThat(count(table, "id", id)).isZero();
        }
        assertThat(count("dream_emotions", "dream_id", b.dream.dreamId())).isZero();
        assertThat(count("dream_analysis_display_keywords", "analysis_id", b.analysis.analysisId())).isZero();
        assertThat(count("dream_scenes", "analysis_id", b.analysis.analysisId())).isZero();
        assertThat(count("dream_entities", "analysis_id", b.analysis.analysisId())).isZero();
        assertThat(count("dream_scene_entities", "dream_scene_id", scene)).isZero();
        assertThat(count("dream_story_versions", "story_id", b.story.storyId())).isZero();
        assertThat(count("dream_generation_jobs", "dream_id", b.dream.dreamId())).isZero();
        assertThat(count("world_bridge_results", "id", ab.response().bridgeId())).isZero();
        assertThat(count("world_bridge_results", "id", bc.response().bridgeId())).isZero();
        assertThat(count("world_bridge_results", "id", ac.response().bridgeId())).isEqualTo(1);
        assertThat(count("ai_generation_logs", "user_id", user)).isEqualTo(1);
        missing(() -> analysisTransactions.get(user, b.analysis.analysisId()));
        missing(() -> storyTransactions.get(user, b.story.storyId()));
        missing(() -> storyTransactions.version(user, b.story.storyId(), oldVersion));
        missing(() -> imageTransactions.get(user, b.image.imageId()));
        missing(() -> images.downloadUrl(user, b.image.imageId()));
        for (var remaining : List.of(a, c)) {
            assertThat(dreams.get(user, remaining.dream.dreamId()).dreamId()).isEqualTo(remaining.dream.dreamId());
            assertThat(imageTransactions.get(user, remaining.image.imageId())).isEqualTo(remaining.image);
        }
        assertThat(imageTransactions.get(other, foreign.image.imageId())).isEqualTo(foreign.image);
        assertThat(jdbc.queryForList("select storage_key,reason from image_cleanup_requests where dream_id=?", b.dream.dreamId()))
                .containsExactly(Map.of("storage_key", key, "reason", "DREAM_DELETED"));
        assertThat(storage.stored).containsKey(key);
        assertThat(storage.deleted).isEmpty(); // 삭제 트랜잭션에서는 외부 IO를 하지 않는다.
    }

    @Test
    void cleanupInsertFailureRollsBackBridgeAndEntireGraph() {
        var a = ready(user, today.minusDays(1));
        var b = ready(user, today);
        var edge = bridges.begin(user, new BridgeRequest(version(a), version(b)), true);
        bridges.finish(edge.input(), "연결");
        var folder = folders.create(user, "삭제 롤백");
        folders.select(user, folder.worldId(), b.dream.dreamId(), folder.revision());
        var before = folders.get(user, folder.worldId());
        jdbc.execute("alter table image_cleanup_requests add constraint reject_test_cleanup check(dream_id is null or dream_id<>"+ b.dream.dreamId()+")");
        try {
            assertThatThrownBy(() -> dreams.delete(user, b.dream.dreamId(), b.dream.revision())).isInstanceOf(RuntimeException.class);
            assertThat(dreams.get(user, b.dream.dreamId()).dreamId()).isEqualTo(b.dream.dreamId());
            assertThat(analysisTransactions.get(user, b.analysis.analysisId()).scenes()).hasSize(1);
            assertThat(storyTransactions.get(user, b.story.storyId()).sections()).isNotEmpty();
            assertThat(imageTransactions.get(user, b.image.imageId())).isEqualTo(b.image);
            assertThat(count("world_bridge_results", "id", edge.response().bridgeId())).isEqualTo(1);
            assertThat(count("dream_story_versions", "story_id", b.story.storyId())).isEqualTo(1);
            assertThat(count("dream_generation_jobs", "dream_id", b.dream.dreamId())).isEqualTo(1);
            assertThat(count("image_cleanup_requests", "dream_id", b.dream.dreamId())).isZero();
            assertThat(folders.get(user, folder.worldId())).isEqualTo(before);
            assertThat(folders.selectedDreams(user, folder.worldId(), null, 20).items()).hasSize(1);
        } finally { jdbc.execute("alter table image_cleanup_requests drop constraint reject_test_cleanup"); }
        dreams.delete(user, b.dream.dreamId(), b.dream.revision());
        assertThat(folders.get(user, folder.worldId()).selectedDreamCount()).isZero();
        assertThat(folders.get(user, folder.worldId()).revision()).isEqualTo(before.revision() + 1);
    }


    @Test
    void outerRollbackRestoresDeletedRowsAndRemovesCleanupRequest() {
        var graph = ready(user, today);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            dreams.delete(user, graph.dream.dreamId(), graph.dream.revision());
            assertThat(count("dream_images", "id", graph.image.imageId())).isZero();
            status.setRollbackOnly();
        });
        assertThat(imageTransactions.get(user, graph.image.imageId())).isEqualTo(graph.image);
        assertThat(count("image_cleanup_requests", "dream_id", graph.dream.dreamId())).isZero();
        assertThat(count("dream_story_versions", "story_id", graph.story.storyId())).isEqualTo(1);
    }

    @Test
    void failedRegenerationQueuesLastSuccessfulKeyAndDraftHasNoCleanupRequest() {
        var graph = ready(user, today);
        String key = imageTransactions.assetKey(user, graph.image.imageId());
        generator.action = input -> { throw new IllegalStateException("test failure"); };
        await(images.generate(user, graph.dream.dreamId(), new ImageRequest(graph.dream.revision(), "test-style", null, true, graph.image.imageVersion())));
        dreams.delete(user, graph.dream.dreamId(), graph.dream.revision());
        assertThat(jdbc.queryForObject("select storage_key from image_cleanup_requests where dream_id=?", String.class, graph.dream.dreamId())).isEqualTo(key);
        var draft = dreams.saveDraft(user, today, new DreamDraftRequest("초안", null, null));
        dreams.delete(user, draft.dreamId(), draft.revision());
        assertThat(count("image_cleanup_requests", "dream_id", draft.dreamId())).isZero();
        missing(() -> dreams.delete(user, draft.dreamId(), draft.revision()));
    }

    @Test
    void lateUploadAfterExpiryAndDeletionIsQueuedIfCompensationFails() {
        var graph = ready(user, today);
        generator.action = input -> {
            jdbc.update("update dream_images set lease_until=? where id=?", java.sql.Timestamp.from(Instant.EPOCH), input.imageId());
            dreams.delete(user, graph.dream.dreamId(), graph.dream.revision());
            storage.failDelete = true;
            return ImagePayloadTest.png();
        };
        missing(() -> await(images.generate(user, graph.dream.dreamId(), new ImageRequest(graph.dream.revision(), "test-style", null, true, graph.image.imageVersion()))));
        assertThat(count("dream_images", "id", graph.image.imageId())).isZero();
        assertThat(jdbc.queryForList("select reason from image_cleanup_requests where image_id=?", String.class, graph.image.imageId()))
                .containsExactlyInAnyOrder("DREAM_DELETED", "DISCARDED_UPLOAD");
        var key = jdbc.queryForObject("select storage_key from image_cleanup_requests where image_id=? and reason='DISCARDED_UPLOAD'", String.class, graph.image.imageId());
        cleanupTransactions.recordDiscardedUpload(user, graph.image.imageId(), key);
        assertThat(count("image_cleanup_requests", "image_id", graph.image.imageId())).isEqualTo(2);
    }
    @Test
    void wrongOwnerOrRevisionCannotDeleteGraphOrEnqueueCleanup() {
        var graph = ready(user, today);
        var other = users.saveAndFlush(User.create(UUID.randomUUID()+"@test.com", "다른사용자")).getId();
        missing(() -> dreams.delete(other, graph.dream.dreamId(), graph.dream.revision()));
        assertThatThrownBy(() -> dreams.delete(user, graph.dream.dreamId(), graph.dream.revision()+1))
                .isInstanceOf(BusinessException.class).extracting("errorCode")
                .isEqualTo(com.mongle.backend.domain.dream.exception.DreamErrorCode.VERSION_CONFLICT);
        assertThat(imageTransactions.get(user, graph.image.imageId())).isEqualTo(graph.image);
        assertThat(count("image_cleanup_requests", "image_id", graph.image.imageId())).isZero();
    }

    @Test
    void firstFailedImageWithoutSuccessfulKeyIsRemovedWithoutCleanupRequest() {
        generator.action = input -> { throw new IllegalStateException("test failure"); };
        var graph = ready(user, today);
        assertThat(graph.image.resultRevision()).isNull();
        dreams.delete(user, graph.dream.dreamId(), graph.dream.revision());
        missing(() -> imageTransactions.get(user, graph.image.imageId()));
        assertThat(count("image_cleanup_requests", "image_id", graph.image.imageId())).isZero();
    }

}
