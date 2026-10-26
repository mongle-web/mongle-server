package com.mongle.backend.domain.payment;
import org.springframework.data.jpa.repository.JpaRepository;
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransactionBinding,String> {}
