package com.mongle.backend.domain.dream.interpretation;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface RecentInterpretationRepository extends JpaRepository<RecentInterpretation, Long> {
    Optional<RecentInterpretation> findByUserId(Long userId);
}
