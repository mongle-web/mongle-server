package com.mongle.backend.domain.auth.repository;

import com.mongle.backend.domain.auth.entity.RefreshSession;
import com.mongle.backend.domain.user.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RefreshSessionRepository extends JpaRepository<RefreshSession, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RefreshSession s join fetch s.user where s.tokenHash = :hash")
    Optional<RefreshSession> findByTokenHashForUpdate(@Param("hash") String hash);

    @Query("select s.user from RefreshSession s where s.id = :id and s.user.id = :userId and s.expiresAt > :now")
    Optional<User> findActiveUser(@Param("id") Long id, @Param("userId") Long userId,
                                 @Param("now") LocalDateTime now);
}
