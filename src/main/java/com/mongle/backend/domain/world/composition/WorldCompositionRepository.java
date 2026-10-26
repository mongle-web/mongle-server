package com.mongle.backend.domain.world.composition;

import com.mongle.backend.global.common.GenerationStatus;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;

import java.time.Instant;
import java.util.*;

public interface WorldCompositionRepository extends JpaRepository<WorldComposition, Long> {
    Optional<WorldComposition> findByWorldIdAndUserId(Long worldId, Long userId);

    interface Candidate {
        Long getWorldId();

        Long getUserId();
    }

    @Query(
            "select w.worldId as worldId,w.userId as userId from WorldComposition w where w.status"
                    + " in :statuses and (w.claimToken is null or w.claimUntil<=:now or"
                    + " w.leaseUntil<=:now) order by w.worldId")
    List<Candidate> runnable(List<GenerationStatus> statuses, Instant now, Pageable page);

    @Query(
            "select (count(e)>0) from WorldCompositionEntry e,WorldComposition w where"
                    + " e.worldId=w.worldId and e.userId=:user and e.dreamId=:dream and"
                    + " e.candidate=true and w.status in :statuses and w.leaseUntil>:now")
    boolean hasActiveSource(Long user, Long dream, List<GenerationStatus> statuses, Instant now);

    @Query(
            "select w from WorldComposition w where w.userId=:user and (w.targetDreamId=:dream or"
                    + " exists(select 1 from WorldCompositionEntry e where e.worldId=w.worldId and"
                    + " e.dreamId=:dream)) order by w.worldId")
    List<WorldComposition> usingDream(Long user, Long dream);
}
