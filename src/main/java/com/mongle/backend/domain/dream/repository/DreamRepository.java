package com.mongle.backend.domain.dream.repository;

import com.mongle.backend.domain.dream.entity.Dream;
import com.mongle.backend.domain.dream.entity.DreamRecordStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.Optional;

public interface DreamRepository extends JpaRepository<Dream, Long> {
    Optional<Dream> findByIdAndUserId(Long id, Long userId);
    Optional<Dream> findByUserIdAndDreamedAt(Long userId, LocalDate date);
    boolean existsByUserIdAndDreamedAt(Long userId, LocalDate date);
    Slice<Dream> findByUserIdAndRecordStatusNotOrderByDreamedAtDescIdDesc(
            Long userId, DreamRecordStatus status, Pageable pageable);
}
