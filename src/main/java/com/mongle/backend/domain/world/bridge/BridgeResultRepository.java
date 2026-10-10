package com.mongle.backend.domain.world.bridge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface BridgeResultRepository extends JpaRepository<BridgeResult, Long> {
    Optional<BridgeResult> findByIdAndUserId(Long id, Long userId);

    Optional<BridgeResult> findByUserIdAndBeforeVersionIdAndAfterVersionIdAndPromptVersionAndSettingsHash(
            Long userId, Long beforeId, Long afterId, String promptVersion, String settingsHash);

    // DreamService의 사용자 잠금과 같은 트랜잭션에서, 성공 버전 삭제보다 먼저 실행한다.
    @Modifying
    @Query("delete from BridgeResult b where b.user.id=:userId and (b.beforeDream.id=:dreamId or b.afterDream.id=:dreamId)")
    int deleteForDream(@Param("userId") Long userId, @Param("dreamId") Long dreamId);
}
