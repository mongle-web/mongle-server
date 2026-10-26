package com.mongle.backend.domain.dream.story;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DreamStoryRepository extends JpaRepository<DreamStory, Long> {
    Optional<DreamStory> findByAnalysisIdAndUserId(Long analysisId, Long userId);

    Optional<DreamStory> findByIdAndUserId(Long id, Long userId);

}
