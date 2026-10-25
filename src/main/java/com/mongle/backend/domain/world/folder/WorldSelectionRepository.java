package com.mongle.backend.domain.world.folder;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorldSelectionRepository extends JpaRepository<WorldSelection, Long> {
    boolean existsByFolderIdAndDreamId(Long worldId, Long dreamId);


}
