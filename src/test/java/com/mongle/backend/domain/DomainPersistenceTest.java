package com.mongle.backend.domain;

import com.mongle.backend.domain.ai.entity.AiGenerationLog;
import com.mongle.backend.domain.ai.entity.AiTaskType;
import com.mongle.backend.domain.ai.repository.AiGenerationLogRepository;
import com.mongle.backend.domain.dream.entity.Dream;
import com.mongle.backend.domain.dream.entity.DreamEntity;
import com.mongle.backend.domain.dream.entity.DreamEntityType;
import com.mongle.backend.domain.dream.entity.DreamScene;
import com.mongle.backend.domain.dream.entity.DreamSceneEntity;
import com.mongle.backend.domain.dream.entity.DreamSceneEntityId;
import com.mongle.backend.domain.dream.repository.DreamRepository;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.common.GenerationStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DomainPersistenceTest {

    @Autowired private EntityManager entityManager;
    @Autowired private UserRepository userRepository;
    @Autowired private DreamRepository dreamRepository;
    @Autowired private AiGenerationLogRepository logRepository;

    @Test
    void persistsAndReloadsDreamGraphWithCompositeKeyAndStringEnums() {
        User user = createUser();
        Dream dream = dreamRepository.save(Dream.builder()
                .user(user)
                .originalText("낯선 도서관에서 고양이를 만났다.")
                .dreamedAt(LocalDate.of(2026, 10, 1))
                .representativeEmotion("호기심")
                .build());
        DreamScene scene = DreamScene.builder()
                .dream(dream).sequenceNo(1).content("도서관에 들어갔다.").build();
        DreamEntity character = DreamEntity.builder()
                .dream(dream).entityType(DreamEntityType.CHARACTER).name("고양이")
                .description("말하는 고양이").build();
        entityManager.persist(scene);
        entityManager.persist(character);
        DreamSceneEntity link = DreamSceneEntity.link(scene, character);
        entityManager.persist(link);
        entityManager.flush();
        entityManager.clear();

        Dream reloaded = dreamRepository.findById(dream.getId()).orElseThrow();
        assertThat(reloaded.getUser().getId()).isEqualTo(user.getId());
        assertThat(entityManager.getEntityManagerFactory().getPersistenceUnitUtil()
                .isLoaded(reloaded, "user")).isFalse();
        assertThat(reloaded.getOriginalText()).isEqualTo("낯선 도서관에서 고양이를 만났다.");
        assertThat(reloaded.getDreamedAt()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(reloaded.getAnalysisStatus()).isEqualTo(GenerationStatus.PENDING);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();

        DreamSceneEntity reloadedLink = entityManager.find(DreamSceneEntity.class,
                new DreamSceneEntityId(scene.getId(), character.getId()));
        assertThat(reloadedLink.getDreamScene().getContent()).isEqualTo("도서관에 들어갔다.");
        assertThat(reloadedLink.getDreamScene().isDisconnectedFromPrevious()).isFalse();
        assertThat(reloadedLink.getDreamEntity().getEntityType()).isEqualTo(DreamEntityType.CHARACTER);
        assertThat(entityManager.createNativeQuery("select analysis_status from dreams where id = :id")
                .setParameter("id", dream.getId()).getSingleResult()).isEqualTo("PENDING");
        assertThat(entityManager.createNativeQuery("select entity_type from dream_entities where id = :id")
                .setParameter("id", character.getId()).getSingleResult()).isEqualTo("CHARACTER");
    }

    @Test
    void auditsUpdatesWithoutChangingCreationTime() {
        User user = createUser();
        Dream dream = dreamRepository.saveAndFlush(Dream.builder()
                .user(user).originalText("첫 기록").build());
        LocalDateTime createdAt = dream.getCreatedAt();
        LocalDateTime updatedAt = dream.getUpdatedAt();
        // 기능별 수정 메서드는 다음 PR에서 추가하므로 dirty checking을 직접 일으킨다.
        org.springframework.test.util.ReflectionTestUtils.setField(dream, "originalText", "수정한 기록");
        entityManager.flush();
        entityManager.clear();

        Dream reloaded = dreamRepository.findById(dream.getId()).orElseThrow();
        assertThat(reloaded.getOriginalText()).isEqualTo("수정한 기록");
        assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
        assertThat(reloaded.getUpdatedAt()).isAfter(updatedAt);
        assertThat(reloaded.getDreamedAt()).isNull();
        assertThat(reloaded.getRepresentativeEmotion()).isNull();
    }

    @Test
    void persistsAiUsageCostsWithoutFloatingPointLoss() {
        AiGenerationLog log = logRepository.saveAndFlush(AiGenerationLog.builder()
                .user(createUser()).taskType(AiTaskType.DREAM_STRUCTURE)
                .modelName("test-model").promptVersion("v1")
                .inputTokens(100).outputTokens(50)
                .actualCost(new BigDecimal("0.00012345"))
                .baselineModel("test-baseline").baselineCost(new BigDecimal("0.00054321"))
                .latencyMs(200L).success(true).build());
        entityManager.clear();

        AiGenerationLog reloaded = logRepository.findById(log.getId()).orElseThrow();
        assertThat(reloaded.getActualCost()).isEqualByComparingTo("0.00012345");
        assertThat(reloaded.getBaselineCost()).isEqualByComparingTo("0.00054321");
        assertThat(reloaded.getCachedInputTokens()).isZero();
        assertThat(reloaded.getTaskType()).isEqualTo(AiTaskType.DREAM_STRUCTURE);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.isSuccess()).isTrue();
    }

    @Test
    void rejectsLinkingScenesToEntitiesFromAnotherDream() {
        User user = createUser();
        Dream first = Dream.builder().user(user).originalText("첫 꿈").build();
        Dream second = Dream.builder().user(user).originalText("다른 꿈").build();
        DreamScene scene = DreamScene.builder().dream(first).sequenceNo(1).content("장면").build();
        DreamEntity entity = DreamEntity.builder()
                .dream(second).entityType(DreamEntityType.PLACE).name("도서관").build();

        assertThatThrownBy(() -> DreamSceneEntity.link(scene, entity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("같은 꿈");
    }

    @Test
    void databaseRejectsDuplicateSceneEntityLinks() {
        Dream dream = dreamRepository.save(Dream.builder()
                .user(createUser()).originalText("꿈").build());
        DreamScene scene = DreamScene.builder().dream(dream).sequenceNo(1).content("장면").build();
        DreamEntity entity = DreamEntity.builder()
                .dream(dream).entityType(DreamEntityType.SYMBOL).name("열쇠").build();
        entityManager.persist(scene);
        entityManager.persist(entity);
        entityManager.persist(DreamSceneEntity.link(scene, entity));
        entityManager.flush();

        assertThatThrownBy(() -> entityManager.createNativeQuery(
                        "insert into dream_scene_entities (dream_scene_id, dream_entity_id) values (:scene, :entity)")
                .setParameter("scene", scene.getId()).setParameter("entity", entity.getId()).executeUpdate())
                .isInstanceOf(jakarta.persistence.PersistenceException.class);
    }

    private User createUser() {
        return userRepository.saveAndFlush(User.builder()
                .email("test@example.com").nickname("몽글").build());
    }
}
