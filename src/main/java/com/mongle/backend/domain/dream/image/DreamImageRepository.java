package com.mongle.backend.domain.dream.image;

import com.mongle.backend.global.common.GenerationStatus;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface DreamImageRepository extends JpaRepository<DreamImage, Long> {
    boolean existsByUserIdAndAnalysis_Dream_IdAndStatusAndLeaseUntilAfter(
            Long userId, Long dreamId, GenerationStatus status, Instant now);

    Optional<DreamImage> findByIdAndUserId(Long id, Long userId);

    Optional<DreamImage> findByAnalysisIdAndUserId(Long analysisId, Long userId);
}
