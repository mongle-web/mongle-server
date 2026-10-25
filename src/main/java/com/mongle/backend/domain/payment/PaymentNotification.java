package com.mongle.backend.domain.payment;
import com.mongle.backend.global.common.BaseCreatedEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
@Entity @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
@Table(name="payment_notifications",indexes=@Index(name="idx_payment_notification_order",columnList="order_id"))
public class PaymentNotification extends BaseCreatedEntity {
    @Id @Column(name="event_hash",length=64) private String eventHash;
    @Column(name="order_id",nullable=false) private Long orderId;
    @Column(name="provider_transaction",nullable=false,length=40) private String providerTransaction;
    @Column(name="state_code",nullable=false) private int stateCode;
    @Column(nullable=false) private long amount;
    @Column(name="responded_at",nullable=false) private Instant respondedAt;
    static PaymentNotification create(PayAppFeedback event,Long orderId) {
        var n=new PaymentNotification(); n.eventHash=event.eventHash(); n.orderId=orderId;
        n.providerTransaction=event.transactionId(); n.stateCode=event.state(); n.amount=event.amount(); n.respondedAt=event.respondedAt(); return n;
    }
}
