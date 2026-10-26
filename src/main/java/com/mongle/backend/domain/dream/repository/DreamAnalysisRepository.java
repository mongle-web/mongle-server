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

}
