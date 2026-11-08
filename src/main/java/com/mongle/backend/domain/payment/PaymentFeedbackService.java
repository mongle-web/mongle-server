package com.mongle.backend.domain.payment;

import com.mongle.backend.domain.credit.*;
import com.mongle.backend.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class PaymentFeedbackService {
    private final PaymentOrderRepository orders;
    private final PaymentNotificationRepository notifications;
    private final PaymentTransactions transactions;
    private final PayAppFeedbackVerifier verifier;
    private final CreditService credits;
    private final PaymentRefundRepository refunds;

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void accept(PayAppFeedback event) {
        // 사용자 행 잠금보다 먼저 읽은 가변 엔티티를 잠금 뒤 그대로 재사용하지 않는다.
        Long user=orders.findOwnerByPublicId(event.orderId())
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.NOT_FOUND));
        transactions.requireAccountingUser(user);
        var order=orders.findByPublicIdAndUserId(event.orderId(),user)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.NOT_FOUND));
        if(!PaymentHashes.equal(order.getNotificationToken(),event.token())) invalid();
        if(event.partialCancellation()) {
            if(event.originalAmount()!=order.getPrice() || event.amount()>order.getPrice()) invalid();
            transactions.bind(order,event.originalTransaction()); order.bindTransaction(event.originalTransaction());
        } else {
            if(event.amount()!=order.getPrice()) invalid();
            order.bindTransaction(event.transactionId());
        }
        transactions.bind(order,event.transactionId());
        // 이미 저장한 정확히 같은 통보는 시각 창이 지나도 재지급 없이 ACK한다.
        if(notifications.existsById(event.eventHash())) return;
        verifier.requireFresh(event);
        // 운영 회수 완료는 종결 상태다. 검증된 늦은 통보는 감사용으로 보존하되 재지급/재보류하지 않는다.
        if(order.getState()==PaymentOrder.State.CREDIT_RECLAIMED) {
            notifications.saveAndFlush(PaymentNotification.create(event,order.getId()));
            transactions.retainAccountingRecords(user);
            return;
        }
        // A fresh replay can have a different event hash while referring to the same settled cancellation.
        boolean confirmedCancellation = event.state() == 9 || event.state() == 64 || event.partialCancellation();
        if ((confirmedCancellation && refunds.countMatchedCancellation(order.getId(), event.transactionId(),
                event.amount(), !event.partialCancellation()) > 0)
                || (order.getState() == PaymentOrder.State.REFUND_SETTLED && !confirmedCancellation)) {
            notifications.saveAndFlush(PaymentNotification.create(event, order.getId()));
            transactions.retainAccountingRecords(user);
            return;
        }
        switch(event.state()) {
            case 1,10 -> order.pending();
            case 4 -> {
                if(order.getState()==PaymentOrder.State.CANCELLED || order.getState()==PaymentOrder.State.PAYMENT_REVIEW) {
                    order.review();
                } else if(order.getCreditGrantId()==null) {
                    var grant=credits.grant(user,CreditSource.PAYMENT,sourceKey(order),order.getCredits());
                    order.paid(grant.grantId(), event.approvedAt());
                }
            }
            case 8,32,9,64,70,71 -> {
                if(order.getCreditGrantId()!=null) credits.holdPayment(user,sourceKey(order));
                if(event.partialCancellation()) order.review(); else order.cancelled();
            }
            default -> invalid();
        }
        notifications.saveAndFlush(PaymentNotification.create(event,order.getId()));
        orders.flush();
        transactions.retainAccountingRecords(user);
    }
    static String sourceKey(PaymentOrder order) { return "payapp:"+order.getPublicId(); }
    private static void invalid() { throw new BusinessException(PaymentErrorCode.INVALID_FEEDBACK); }
}
