package com.mongle.backend.domain.dream.analysis;

import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface StoredAnalysisRepository extends JpaRepository<DreamAnalysis, Long> {
    Optional<DreamAnalysis> findBySourceDreamIdAndUserId(Long dreamId, Long userId);

    Optional<DreamAnalysis> findByIdAndUserId(Long id, Long userId);
}
