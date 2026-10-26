package com.mongle.backend.domain.dream.image;

import com.mongle.backend.global.common.GenerationStatus;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface DreamImageRepository extends JpaRepository<DreamImage, Long> {
    interface ExpiredAttempt { Long getImageId(); Long getUserId(); }

    @org.springframework.data.jpa.repository.Query("select i.id as imageId, i.user.id as userId from DreamImage i"
            + " where i.status = :status and (i.leaseUntil <= :now or i.leaseUntil is null) order by i.id")
    java.util.List<ExpiredAttempt> expiredAttempts(GenerationStatus status, Instant now,
            org.springframework.data.domain.Pageable page);

    boolean existsByStorageKey(String storageKey);
    boolean existsByUserIdAndAnalysis_Dream_IdAndStatusAndLeaseUntilAfter(
            Long userId, Long dreamId, GenerationStatus status, Instant now);

    Optional<DreamImage> findByIdAndUserId(Long id, Long userId);

    Optional<DreamImage> findByAnalysisIdAndUserId(Long analysisId, Long userId);
}
