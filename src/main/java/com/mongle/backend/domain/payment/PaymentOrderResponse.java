package com.mongle.backend.domain.payment;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.*;
@Schema(description="본인 주문. 결제창 URL 발급은 결제 완료가 아닙니다. PAID와 creditGranted를 확인하세요. CREDIT_RECLAIMED는 전액 승인취소된 미사용 구매분의 내부 회수 완료입니다. REFUND_SETTLED는 접수 건의 PG 취소와 내부 정산이 완료된 상태입니다. creditGranted는 과거 지급 연결 여부이며 현재 잔액이 아닙니다. UNKNOWN은 결과 미확정이며 같은 주문으로 결제를 자동 재요청하지 않습니다.")
public record PaymentOrderResponse(String orderId, String productId, String productName, long price,
        String currency, long credits, PaymentOrder.State state, String paymentUrl, boolean creditGranted,
        LocalDateTime createdAt,
        @Schema(description = "환불 정산 및 이후 재대조에서 확인한 주문 누적 취소액. 확인된 정산이 없으면 null.")
        Long confirmedCancellationAmount) {
    static PaymentOrderResponse from(PaymentOrder o,Instant now) {
        return from(o, now, null);
    }

    static PaymentOrderResponse from(PaymentOrder o,Instant now,Long confirmedCancellationAmount) {
        var state=o.getState();
        if(state==PaymentOrder.State.REQUESTING && !o.getRequestDeadline().isAfter(now)) state=PaymentOrder.State.UNKNOWN;
        return new PaymentOrderResponse(o.getPublicId(),o.getProductId(),o.getProductName(),o.getPrice(),
                o.getCurrency(),o.getCredits(),state,state==PaymentOrder.State.READY?o.getPaymentUrl():null,
                o.getCreditGrantId()!=null,o.getCreatedAt(),confirmedCancellationAmount);
    }
}
