package com.mongle.backend.domain.dream.repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DreamAnalysisRepository {
    private final EntityManager entityManager;

    public void detachFromDream(Long dreamId) {
        // 분석 내용과 장면·요소 간 연결은 유지하고, 삭제할 원문과의 FK만 해제한다.
        entityManager
                .createQuery("update DreamScene s set s.dream = null where s.dream.id = :id")
                .setParameter("id", dreamId)
                .executeUpdate();
        entityManager
                .createQuery("update DreamEntity e set e.dream = null where e.dream.id = :id")
                .setParameter("id", dreamId)
                .executeUpdate();
    }
}
