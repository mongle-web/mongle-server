package com.mongle.backend.domain.dream.repository;

import com.mongle.backend.domain.dream.entity.Dream;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DreamRepository extends JpaRepository<Dream, Long> {
}
