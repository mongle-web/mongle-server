package com.mongle.backend.domain.user.withdrawal;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.data.domain.Pageable;

public interface SocialUnlinkTaskRepository extends JpaRepository<SocialUnlinkTask,String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from SocialUnlinkTask t where t.identityHash=:hash")
    Optional<SocialUnlinkTask> lock(@Param("hash") String hash);
    @Query("select t.identityHash from SocialUnlinkTask t where t.state in :states and t.nextRunAt<=:now order by t.nextRunAt")
    List<String> due(@Param("states") List<SocialUnlinkTask.State> states,@Param("now") LocalDateTime now,Pageable page);
    boolean existsByUserIdAndStateIn(Long user,List<SocialUnlinkTask.State> states);
}
