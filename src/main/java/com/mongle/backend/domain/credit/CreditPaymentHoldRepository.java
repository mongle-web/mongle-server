package com.mongle.backend.domain.credit;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CreditPaymentHoldRepository extends JpaRepository<CreditPaymentHold,Long> {
    boolean existsByUserId(Long userId);
}
