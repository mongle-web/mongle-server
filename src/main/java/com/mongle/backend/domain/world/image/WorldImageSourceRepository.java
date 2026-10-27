package com.mongle.backend.domain.world.image;

import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface WorldImageSourceRepository extends JpaRepository<WorldImageSource, Long> {
    @Modifying @Query("delete from WorldImageSource s where s.imageId=:image and s.candidate=:candidate")
    int deleteLane(Long image, boolean candidate);
    @Modifying @Query("update WorldImageSource s set s.candidate=false where s.imageId=:image and s.candidate=true")
    int publish(Long image);
    @Query("select distinct s.imageId from WorldImageSource s where s.userId=:user and s.dreamId=:dream order by s.imageId")
    List<Long> imagesForDream(Long user, Long dream);
    boolean existsByImageIdAndDreamIdAndCandidate(Long image, Long dream, boolean candidate);
    @Modifying @Query("delete from WorldImageSource s where s.userId=:user and s.dreamId=:dream")
    int deleteForDream(Long user, Long dream);
    @Query("select (count(s)>0) from WorldImageSource s,Dream d where s.imageId=:image and s.candidate=false and d.id=s.dreamId and d.user.id=s.userId and d.sourceRevision<>s.sourceRevision")
    boolean sourceChanged(Long image);
}
