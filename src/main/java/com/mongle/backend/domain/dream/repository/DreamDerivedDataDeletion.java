package com.mongle.backend.domain.dream.repository;

import com.mongle.backend.domain.dream.image.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

/** DreamService의 소유권/revision/생성 보호 검사 및 사용자 잠금 안에서만 실행한다. */
@Repository
@RequiredArgsConstructor
public class DreamDerivedDataDeletion {
    private final EntityManager em;
    private final ImageCleanupRegistration cleanupRegistration;
    private static final String ANALYSES =
            "select id from dream_analyses where source_dream_id=:dream and user_id=:user";
    private static final String SCENES = "select id from dream_scenes where user_id=:user and"
            + " (dream_id=:dream or analysis_id in (" + ANALYSES + "))";
    private static final String ENTITIES = "select id from dream_entities where user_id=:user and"
            + " (dream_id=:dream or analysis_id in (" + ANALYSES + "))";

    @Transactional(propagation = Propagation.MANDATORY)
    public void delete(Long userId, Long dreamId) {
        em.flush();
        // 현재 성공 파일만 확인할 수 있다. 이전 파일 이력은 이 테이블에 존재하지 않는다.
        var images = em.createQuery("select i from DreamImage i where i.user.id=:user"
                + " and i.analysis.sourceDreamId=:dream", DreamImage.class)
                .setParameter("user", userId).setParameter("dream", dreamId).getResultList();
        for (var image : images) {
            if (image.getStorageKey() != null)
                cleanupRegistration.register(userId, dreamId, image.getId(),
                        image.getStorageKey(), ImageCleanupRequest.Reason.DREAM_DELETED);
        }
        // 기록 실패 시 브리지 제거를 포함한 호출자 트랜잭션 전체가 롤백되어야 한다.
        em.flush();
        execute("delete from dream_story_versions where story_id in (select id from dream_stories"
                + " where analysis_id in (" + ANALYSES + "))", userId, dreamId);
        execute("delete from dream_stories where analysis_id in (" + ANALYSES + ")", userId, dreamId);
        execute("delete from dream_images where analysis_id in (" + ANALYSES + ")", userId, dreamId);
        // 분석 없이 저장된 기존 장면/요소도 포함한다. 교차 연결부터 제거한다.
        execute("delete from dream_scene_entities where dream_scene_id in (" + SCENES
                + ") or dream_entity_id in (" + ENTITIES + ")", userId, dreamId);
        execute("delete from dream_scenes where user_id=:user and (dream_id=:dream"
                + " or analysis_id in (" + ANALYSES + "))", userId, dreamId);
        execute("delete from dream_entities where user_id=:user and (dream_id=:dream"
                + " or analysis_id in (" + ANALYSES + "))", userId, dreamId);
        execute("delete from dream_analysis_display_keywords where analysis_id in ("
                + ANALYSES + ")", userId, dreamId);
        execute("delete from dream_analyses where source_dream_id=:dream and user_id=:user", userId, dreamId);
        // Guard에서 읽은 엔티티와 벌크 삭제 상태가 섞이지 않도록 다시 로딩한다.
        em.clear();
    }

    private void execute(String sql, Long userId, Long dreamId) {
        em.createNativeQuery(sql).setParameter("user", userId).setParameter("dream", dreamId)
                .executeUpdate();
    }
}
