package com.mongle.backend.domain.payment;

import com.mongle.backend.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Getter @Entity
@Table(name="payment_orders", uniqueConstraints={
    @UniqueConstraint(name="uk_payment_order_public",columnNames="public_id"),
    @UniqueConstraint(name="uk_payment_order_request",columnNames={"user_id","request_id"})},
    indexes=@Index(name="idx_payment_order_user",columnList="user_id,id"))
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class PaymentOrder extends BaseEntity {
    public enum State { REQUESTING, READY, REQUEST_REJECTED, UNKNOWN, PENDING, PAID, CANCELLED, PAYMENT_REVIEW, CREDIT_RECLAIMED }
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="public_id",nullable=false,length=36) private String publicId;
    @Column(name="user_id",nullable=false) private Long userId;
    @Column(name="request_id",nullable=false,length=36) private String requestId;
    @Column(name="input_hash",nullable=false,length=64) private String inputHash;
    @Column(name="notification_token",nullable=false,length=36) private String notificationToken;
    @Column(name="product_id",nullable=false,length=50) private String productId;
    @Column(name="product_name",nullable=false,length=160) private String productName;
    @Column(nullable=false) private long price;
    @Column(nullable=false,length=3) private String currency;
    @Column(nullable=false) private long credits;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=24) private State state;
    @Column(name="provider_transaction",length=40) private String providerTransaction;
    @Column(name="payment_url",length=2048) private String paymentUrl;
    @Column(name="credit_grant_id") private Long creditGrantId;
    @Column(name="request_deadline",nullable=false) private Instant requestDeadline;
    @Version @Column(nullable=false) private long version;

    static PaymentOrder create(Long user,PaymentOrderRequest request,CreditProduct p,Instant deadline) {
        var o=new PaymentOrder(); o.userId=user; o.publicId=UUID.randomUUID().toString();
        o.requestId=request.requestId().toString(); o.inputHash=inputHash(request);
        o.notificationToken=UUID.randomUUID().toString(); o.productId=p.productId();
        o.productName=p.name(); o.price=p.price(); o.currency=p.currency(); o.credits=p.credits();
        o.state=State.REQUESTING; o.requestDeadline=deadline; return o;
    }
    static String inputHash(PaymentOrderRequest r) { return PaymentHashes.sha256(r.productId()+":"+r.buyerPhone()); }
    void bindTransaction(String transaction) {
        if(providerTransaction != null && !providerTransaction.equals(transaction))
            throw new com.mongle.backend.global.error.BusinessException(PaymentErrorCode.TRANSACTION_CONFLICT);
        providerTransaction=transaction;
    }
    void requestFinished(PaymentGateway.Result result) {
        if(result.outcome()==PaymentGateway.Outcome.READY) {
            bindTransaction(result.transactionId());
            if(state==State.REQUESTING || state==State.UNKNOWN) { state=State.READY; paymentUrl=result.paymentUrl(); }
        } else if(state==State.REQUESTING || state==State.UNKNOWN) {
            state=result.outcome()==PaymentGateway.Outcome.REJECTED ? State.REQUEST_REJECTED : State.UNKNOWN;
        }
    }
    void pending() { if(state==State.REQUESTING || state==State.READY || state==State.UNKNOWN) state=State.PENDING; }
    void paid(Long grantId) { state=State.PAID; creditGrantId=grantId; paymentUrl=null; }
    void cancelled() { state=creditGrantId==null ? State.CANCELLED : State.PAYMENT_REVIEW; paymentUrl=null; }
    void review() { state=State.PAYMENT_REVIEW; paymentUrl=null; }
}
