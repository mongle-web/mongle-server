package com.mongle.backend.domain.world.image;

import com.mongle.backend.global.common.GenerationStatus;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.*;

public interface WorldImageRepository extends JpaRepository<WorldImage, Long> {
    Optional<WorldImage> findByWorldIdAndUserId(Long worldId, Long userId);
    Optional<WorldImage> findByIdAndUserId(Long id, Long userId);
    boolean existsByStorageKey(String key);
    interface ExpiredAttempt { Long getImageId(); Long getUserId(); }
    @Query("select i.id as imageId,i.userId as userId from WorldImage i where i.status=:status and (i.leaseUntil<=:now or i.leaseUntil is null) order by i.id")
    List<ExpiredAttempt> expiredAttempts(GenerationStatus status, Instant now, Pageable page);
}
