package com.mongle.backend.domain.payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.util.*;
public interface PaymentOrderRepository extends JpaRepository<PaymentOrder,Long> {
    Optional<PaymentOrder> findByUserIdAndRequestId(Long userId,String requestId);
    Optional<PaymentOrder> findByPublicId(String id);
    @Query("select o.userId from PaymentOrder o where o.publicId=:id")
    Optional<Long> findOwnerByPublicId(@Param("id") String id);
    Optional<PaymentOrder> findByPublicIdAndUserId(String id,Long userId);
    @Query("select o from PaymentOrder o where o.userId=:user and (:before is null or o.id<:before) order by o.id desc")
    List<PaymentOrder> page(@Param("user") Long userId,@Param("before") Long before,Pageable page);
}
