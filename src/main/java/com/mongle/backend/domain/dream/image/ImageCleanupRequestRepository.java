package com.mongle.backend.domain.dream.image;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.*;

public interface ImageCleanupRequestRepository extends JpaRepository<ImageCleanupRequest, Long> {
    boolean existsByStorageKey(String storageKey);
    boolean existsByStorageKeyAndDeletionStartedAtIsNotNull(String storageKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ImageCleanupRequest r where r.id=:id")
    Optional<ImageCleanupRequest> findForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ImageCleanupRequest r where r.storageKey=:key")
    Optional<ImageCleanupRequest> findKeyForUpdate(@Param("key") String key);

    @Query("select r.userId from ImageCleanupRequest r where r.id=:id")
    Optional<Long> owner(@Param("id") Long id);

    @Query("select r.id from ImageCleanupRequest r where"
            + " (r.status in :waiting and (r.nextRunAt is null or r.nextRunAt<=:now))"
            + " or (r.status=:processing and r.leaseUntil<=:now) order by r.id")
    List<Long> candidates(@Param("waiting") Collection<ImageCleanupRequest.Status> waiting,
            @Param("processing") ImageCleanupRequest.Status processing,
            @Param("now") Instant now, Pageable pageable);
}
