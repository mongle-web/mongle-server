package com.mongle.backend.domain.credit;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
public interface CreditAllocationRepository extends JpaRepository<CreditAllocation, Long> {
    @Query("select a from CreditAllocation a join fetch a.grant where a.reservation.id=:id order by a.id")
    List<CreditAllocation> forReservation(@Param("id") Long id);
}
