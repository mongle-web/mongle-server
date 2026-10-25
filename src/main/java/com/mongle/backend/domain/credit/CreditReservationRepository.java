package com.mongle.backend.domain.credit;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface CreditReservationRepository extends JpaRepository<CreditReservation, Long> {
    Optional<CreditReservation> findByUserIdAndRequestHash(Long userId, String hash);
    boolean existsByUserIdAndUsageAndTargetHash(Long userId, CreditUsage usage, String hash);
    Optional<CreditReservation> findByIdAndUserId(Long id, Long userId);
}
