package com.mongle.backend.domain.ai.repository;

import com.mongle.backend.domain.ai.entity.AiGenerationLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiGenerationLogRepository extends JpaRepository<AiGenerationLog, Long> {
}
