package com.mongle.backend.domain.payment;
import jakarta.persistence.*;
import lombok.*;
@Entity @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
@Table(name="payment_transaction_bindings",indexes=@Index(name="idx_payment_binding_order",columnList="order_id"))
public class PaymentTransactionBinding {
    @Id @Column(name="transaction_id",length=40) private String transactionId;
    @Column(name="order_id",nullable=false) private Long orderId;
    static PaymentTransactionBinding create(String transactionId,Long orderId) {
        var b=new PaymentTransactionBinding(); b.transactionId=transactionId; b.orderId=orderId; return b;
    }
}
