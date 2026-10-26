package com.mongle.backend.domain.dream.repository;

import com.mongle.backend.domain.dream.analysis.DreamAnalysis;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class DreamAnalysisRepository {
    private final EntityManager entityManager;

    public Optional<DreamAnalysis> findDisplayMetadata(Long dreamId, Long userId) {
        return entityManager
                .createQuery(
                        "select distinct a from DreamAnalysis a left join fetch a.displayKeywords"
                                + " where a.sourceDreamId=:dreamId and a.user.id=:userId",
                        DreamAnalysis.class)
                .setParameter("dreamId", dreamId)
                .setParameter("userId", userId)
                .getResultList()
                .stream()
                .findFirst();
    }

    public void detachFromDream(Long dreamId) {
        // 새 버전에 저장한 원문·감정 스냅샷은 꿈 영구 삭제 후 남기지 않는다.
        entityManager.createQuery("delete from StoryResultVersion v where v.story.analysis.dream.id=:id")
                .setParameter("id", dreamId).executeUpdate();
        entityManager.createQuery("update DreamAnalysis a set a.sourceText=null, a.sourceEmotions=null,"
                + " a.pendingResultJson=null where a.dream.id=:id")
                .setParameter("id", dreamId).executeUpdate();
        // FK 해제 전에 진행 중 이미지 시도를 차단한다. 완료 파일과 이전 성공 결과는 보존한다.
        entityManager
                .createQuery(
                        "update DreamImage i set i.status=:failed, i.failureCode=:code,"
                                + " i.leaseUntil=null, i.version=i.version+1 where i.analysis.id in"
                                + " (select a.id from DreamAnalysis a where a.dream.id=:id)"
                                + " and i.status=:processing")
                .setParameter("id", dreamId)
                .setParameter("failed", com.mongle.backend.global.common.GenerationStatus.FAILED)
                .setParameter(
                        "processing", com.mongle.backend.global.common.GenerationStatus.PROCESSING)
                .setParameter("code", "SOURCE_DELETED")
                .executeUpdate();
        entityManager
                .createQuery(
                        "update DreamAnalysis a set a.dream = null, a.status = :failed,"
                            + " a.failureCode = :code, a.leaseUntil = null where a.dream.id = :id"
                            + " and a.status = :processing")
                .setParameter("id", dreamId)
                .setParameter("failed", com.mongle.backend.global.common.GenerationStatus.FAILED)
                .setParameter(
                        "processing", com.mongle.backend.global.common.GenerationStatus.PROCESSING)
                .setParameter("code", "SOURCE_DELETED")
                .executeUpdate();
        entityManager
                .createQuery("update DreamAnalysis a set a.dream = null where a.dream.id = :id")
                .setParameter("id", dreamId)
                .executeUpdate();
        // 분석 내용과 장면·요소 간 연결은 유지하고, 삭제할 원문과의 FK만 해제한다.
        entityManager
                .createQuery("update DreamScene s set s.dream = null where s.dream.id = :id")
                .setParameter("id", dreamId)
                .executeUpdate();
        entityManager
                .createQuery("update DreamEntity e set e.dream = null where e.dream.id = :id")
                .setParameter("id", dreamId)
                .executeUpdate();
        // Guard에서 로딩한 엔티티도 벌크 FK 해제 상태와 일치시킨다.
        entityManager.clear();
    }
}
