package com.mongle.backend.domain.world.composition;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.Pageable;
import java.util.*;
public interface WorldCompositionEntryRepository extends JpaRepository<WorldCompositionEntry,Long> {
    List<WorldCompositionEntry> findByWorldIdAndCandidateOrderByPositionAsc(Long worldId,boolean candidate);
    @Query("select e from WorldCompositionEntry e where e.worldId=:world and e.candidate=true and e.needsBridge=true order by e.position")
    List<WorldCompositionEntry> pending(Long world,Pageable page);
    @Query("select e from WorldCompositionEntry e where e.worldId=:world and e.candidate=true and e.position<:position order by e.position desc")
    List<WorldCompositionEntry> previous(Long world,long position,Pageable page);
    @Modifying @Query("delete from WorldCompositionEntry e where e.worldId=:world and e.candidate=:candidate")
    int deleteLane(Long world,boolean candidate);
    @Modifying @Query("update WorldCompositionEntry e set e.candidate=false where e.worldId=:world and e.candidate=true")
    int publish(Long world);
    @Modifying @Query("delete from WorldCompositionEntry e where e.worldId=:world") int deleteWorld(Long world);
}
