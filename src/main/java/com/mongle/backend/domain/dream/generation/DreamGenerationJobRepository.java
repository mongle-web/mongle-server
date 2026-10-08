package com.mongle.backend.domain.dream.generation;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DreamGenerationJobRepository extends JpaRepository<DreamGenerationJob, Long> {
    Optional<DreamGenerationJob> findByDreamIdAndUserId(Long dreamId, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from DreamGenerationJob j where j.id = :id")
    Optional<DreamGenerationJob> locked(@Param("id") Long id);

    @Query("""
            select j from DreamGenerationJob j
            where (j.status = :queued and j.nextRunAt <= :now)
               or (j.status = :processing and j.leaseUntil <= :now)
            order by j.nextRunAt, j.id
            """)
    List<DreamGenerationJob> due(
            @Param("now") Instant now,
            @Param("queued") DreamGenerationJob.Status queued,
            @Param("processing") DreamGenerationJob.Status processing,
            Pageable page);

    @Query("""
            select count(j) > 0 from DreamGenerationJob j
            where j.userId = :user and j.id <> :id
              and j.status = :processing and j.leaseUntil > :now
            """)
    boolean userBusy(
            @Param("user") Long user,
            @Param("id") Long id,
            @Param("now") Instant now,
            @Param("processing") DreamGenerationJob.Status processing);

    void deleteByDreamIdAndUserId(Long dreamId, Long userId);
}
