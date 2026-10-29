package com.mongle.backend.domain.world.summary;

import com.mongle.backend.global.common.GenerationStatus;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;

import java.time.Instant;
import java.util.*;

public interface WorldSummaryRepository extends JpaRepository<WorldSummary, Long> {
    Optional<WorldSummary> findByWorldIdAndUserId(Long world, Long user);

    @Query(
            "select s from WorldSummary s where s.status=:pending or (s.status=:processing and"
                + " s.leaseUntil<=:now) order by s.worldId")
    List<WorldSummary> runnable(
            GenerationStatus pending, GenerationStatus processing, Instant now, Pageable page);
}
