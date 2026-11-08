package com.mongle.backend.domain.payment;
import org.springframework.data.jpa.repository.JpaRepository;
public interface PaymentNotificationRepository extends JpaRepository<PaymentNotification,String> {
    java.util.Optional<PaymentNotification> findFirstByOrderIdOrderByRespondedAtDescEventHashDesc(Long orderId);
}
