package com.mongle.backend.domain.dream.story;

import com.mongle.backend.global.common.GenerationStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DreamStoryRepository extends JpaRepository<DreamStory, Long> {
    Optional<DreamStory> findByAnalysisIdAndUserId(Long analysisId, Long userId);

    Optional<DreamStory> findByIdAndUserId(Long id, Long userId);

    @Modifying
    @Query(
            """
            update DreamStory s
            set s.status = :failed, s.failureCode = 'SOURCE_DELETED',
                s.leaseUntil = null, s.version = s.version + 1
            where s.analysis.id in (
                select a.id from DreamAnalysis a where a.dream.id = :dreamId
            ) and s.status = :processing
            """)
    int failProcessingForDeletedDream(
            @Param("dreamId") Long dreamId,
            @Param("processing") GenerationStatus processing,
            @Param("failed") GenerationStatus failed);
}
