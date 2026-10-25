package com.mongle.backend.domain.credit;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
public interface CreditReservationRepository extends JpaRepository<CreditReservation, Long> {
    Optional<CreditReservation> findByUserIdAndRequestHash(Long userId, String hash);
    boolean existsByUserIdAndUsageAndTargetHash(Long userId, CreditUsage usage, String hash);
    @Transactional(readOnly = true)
    @Query("select r from CreditReservation r where r.state=:state and r.expiresAt<=:now order by r.expiresAt,r.id")
    List<CreditReservation> expired(@Param("state") CreditReservation.State state, @Param("now") Instant now, Pageable page);
    Optional<CreditReservation> findByIdAndUserId(Long id, Long userId);
}
