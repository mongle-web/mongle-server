package com.mongle.backend.domain.world.folder;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WorldFolderRepository extends JpaRepository<WorldFolder, Long> {
    Optional<WorldFolder> findByIdAndUserId(Long id, Long userId);

}
