package com.mongle.backend.domain.world.folder;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WorldFolderRepository extends JpaRepository<WorldFolder, Long> {
    Optional<WorldFolder> findByIdAndUserId(Long id, Long userId);

    @Query("select f from WorldFolder f where f.user.id=:user and (:before is null or f.id<:before) order by f.id desc")
    List<WorldFolder> page(@Param("user") Long user, @Param("before") Long before, Pageable pageable);
}
