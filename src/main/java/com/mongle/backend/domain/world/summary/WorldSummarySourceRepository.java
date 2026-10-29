package com.mongle.backend.domain.world.summary;

import org.springframework.data.jpa.repository.*;

import java.util.*;

public interface WorldSummarySourceRepository extends JpaRepository<WorldSummarySource, Long> {
    @Modifying
    @Query("delete from WorldSummarySource s where s.worldId=:world and s.candidate=:candidate")
    int deleteLane(Long world, boolean candidate);

    @Modifying
    @Query(
            "update WorldSummarySource s set s.candidate=false where s.worldId=:world and"
                + " s.candidate=true")
    int publish(Long world);

    @Query(
            "select distinct s.worldId from WorldSummarySource s where s.userId=:user and"
                + " s.dreamId=:dream")
    List<Long> worldsForDream(Long user, Long dream);

    boolean existsByWorldIdAndDreamIdAndCandidate(Long world, Long dream, boolean candidate);
}
