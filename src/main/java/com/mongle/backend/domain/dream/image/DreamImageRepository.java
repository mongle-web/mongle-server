package com.mongle.backend.domain.dream.image;

import com.mongle.backend.global.common.GenerationStatus;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface DreamImageRepository extends JpaRepository<DreamImage, Long> {
    interface ExpiredAttempt { Long getImageId(); Long getUserId(); }

    interface DownloadAsset {
        Long getImageId();
        long getImageVersion();
        String getStorageKey();
    }

    @org.springframework.data.jpa.repository.Query("""
            select i.id as imageId, i.version as imageVersion, i.storageKey as storageKey
            from DreamImage i join i.analysis a join a.dream d
            where i.user.id = :userId and a.user.id = :userId and d.user.id = :userId
              and i.id in :imageIds and i.storageKey is not null and i.storageKey <> ''
            """)
    java.util.List<DownloadAsset> findDownloadAssets(Long userId, java.util.Collection<Long> imageIds);


    @org.springframework.data.jpa.repository.Query("select i.id as imageId, i.user.id as userId from DreamImage i"
            + " where i.status = :status and (i.leaseUntil <= :now or i.leaseUntil is null) order by i.id")
    java.util.List<ExpiredAttempt> expiredAttempts(GenerationStatus status, Instant now,
            org.springframework.data.domain.Pageable page);

    boolean existsByStorageKey(String storageKey);
    boolean existsByUserIdAndAnalysis_Dream_IdAndStatusAndLeaseUntilAfter(
            Long userId, Long dreamId, GenerationStatus status, Instant now);

    Optional<DreamImage> findByIdAndUserId(Long id, Long userId);

    Optional<DreamImage> findByAnalysisIdAndUserId(Long analysisId, Long userId);
}
