package com.mongle.backend.domain.dream.analysis;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import com.mongle.backend.global.common.GenerationStatus;
import java.time.LocalDate;
import java.util.*;

public interface StoredAnalysisRepository extends JpaRepository<DreamAnalysis, Long> {
    Optional<DreamAnalysis> findBySourceDreamIdAndUserId(Long dreamId, Long userId);

    Optional<DreamAnalysis> findByIdAndUserId(Long id, Long userId);

    @Query(
            "select a from DreamAnalysis a where a.user.id=:user and a.status=:status and a.dreamedAt between :from and :to order by a.dreamedAt desc,a.id desc")
    List<DreamAnalysis> recent(
            @Param("user") Long user,
            @Param("status") GenerationStatus status,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            Pageable page);
}
