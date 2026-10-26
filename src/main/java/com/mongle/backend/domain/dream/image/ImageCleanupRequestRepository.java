package com.mongle.backend.domain.dream.image;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ImageCleanupRequestRepository extends JpaRepository<ImageCleanupRequest, Long> {
    boolean existsByStorageKey(String storageKey);
}
