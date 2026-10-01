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
        Dream dream = dreamRepository.save(Dream.create(
                user, "낯선 도서관에서 고양이를 만났다.", LocalDate.of(2026, 10, 1)));
        DreamScene scene = DreamScene.create(dream, 1, "도서관에 들어갔다.", false);
        DreamEntity character = DreamEntity.create(dream, DreamEntityType.CHARACTER, "고양이", "말하는 고양이");
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
        assertThat(reloaded.getEmotions()).isEmpty();
        assertThat(reloaded.getAnalysisStatus()).isEqualTo(GenerationStatus.PENDING);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();

        DreamSceneEntity reloadedLink = entityManager.find(DreamSceneEntity.class,
                new DreamSceneEntityId(scene.getId(), character.getId()));
        assertThat(reloadedLink.getDreamScene().getContent()).isEqualTo("도서관에 들어갔다.");
        assertThat(reloadedLink.getDreamScene().getSequenceNo()).isEqualTo(1);
        assertThat(reloadedLink.getDreamScene().isDisconnectedFromPrevious()).isFalse();
        assertThat(reloadedLink.getDreamEntity().getEntityType()).isEqualTo(DreamEntityType.CHARACTER);
        assertThat(reloadedLink.getDreamEntity().getName()).isEqualTo("고양이");
        assertThat(reloadedLink.getDreamEntity().getDescription()).isEqualTo("말하는 고양이");
        assertThat(entityManager.createNativeQuery("select analysis_status from dreams where id = :id")
                .setParameter("id", dream.getId()).getSingleResult()).isEqualTo("PENDING");
        assertThat(entityManager.createNativeQuery("select entity_type from dream_entities where id = :id")
                .setParameter("id", character.getId()).getSingleResult()).isEqualTo("CHARACTER");
    }

    @Test
    void auditsUpdatesWithoutChangingCreationTime() {
        User user = createUser();
        Dream dream = dreamRepository.saveAndFlush(Dream.create(user, "첫 기록", LocalDate.of(2026, 10, 1)));
        // DB가 저장한 시간 정밀도를 기준으로 생성·수정 시각을 비교한다.
        entityManager.refresh(dream);
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
        assertThat(reloaded.getDreamedAt()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(reloaded.getEmotions()).isEmpty();
    }

    @Test
    void persistsAiUsageCostsWithoutFloatingPointLoss() {
        AiGenerationLog log = logRepository.saveAndFlush(AiGenerationLog.create(
                createUser(), AiTaskType.DREAM_STRUCTURE, "test-model", "v1",
                100, 50, 25, new BigDecimal("0.00012345"),
                "test-baseline", new BigDecimal("0.00054321"), 200L, true));
        entityManager.clear();

        AiGenerationLog reloaded = logRepository.findById(log.getId()).orElseThrow();
        assertThat(reloaded.getActualCost()).isEqualByComparingTo("0.00012345");
        assertThat(reloaded.getBaselineCost()).isEqualByComparingTo("0.00054321");
        assertThat(reloaded.getModelName()).isEqualTo("test-model");
        assertThat(reloaded.getPromptVersion()).isEqualTo("v1");
        assertThat(reloaded.getInputTokens()).isEqualTo(100);
        assertThat(reloaded.getOutputTokens()).isEqualTo(50);
        assertThat(reloaded.getCachedInputTokens()).isEqualTo(25);
        assertThat(reloaded.getBaselineModel()).isEqualTo("test-baseline");
        assertThat(reloaded.getLatencyMs()).isEqualTo(200L);
        assertThat(reloaded.getTaskType()).isEqualTo(AiTaskType.DREAM_STRUCTURE);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.isSuccess()).isTrue();
    }

    @Test
    void rejectsLinkingScenesToEntitiesFromAnotherDream() {
        User user = createUser();
        Dream first = Dream.create(user, "첫 꿈", LocalDate.of(2026, 10, 1));
        Dream second = Dream.create(user, "다른 꿈", LocalDate.of(2026, 10, 2));
        DreamScene scene = DreamScene.create(first, 1, "장면", false);
        DreamEntity entity = DreamEntity.create(second, DreamEntityType.PLACE, "도서관", null);

        assertThatThrownBy(() -> DreamSceneEntity.link(scene, entity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("같은 꿈");
    }

    @Test
    void databaseRejectsDuplicateSceneEntityLinks() {
        Dream dream = dreamRepository.save(Dream.create(createUser(), "꿈", LocalDate.of(2026, 10, 1)));
        DreamScene scene = DreamScene.create(dream, 1, "장면", false);
        DreamEntity entity = DreamEntity.create(dream, DreamEntityType.SYMBOL, "열쇠", null);
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
        return userRepository.saveAndFlush(User.create("test@example.com", "몽글"));
    }
}
