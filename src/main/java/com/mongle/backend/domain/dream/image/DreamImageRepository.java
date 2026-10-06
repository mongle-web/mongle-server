package com.mongle.backend.domain.dream.image;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DreamImageRepository extends JpaRepository<DreamImage, Long> {
    Optional<DreamImage> findByIdAndUserId(Long id, Long userId);

    Optional<DreamImage> findByAnalysisIdAndUserId(Long analysisId, Long userId);
}
