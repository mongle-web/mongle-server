package com.mongle.backend.domain.payment;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.*;
@Schema(description="본인 주문. 결제창 URL 발급은 결제 완료가 아닙니다. PAID와 creditGranted를 확인하세요. UNKNOWN은 결과 미확정이며 같은 주문으로 결제를 자동 재요청하지 않습니다.")
public record PaymentOrderResponse(String orderId, String productId, String productName, long price,
        String currency, long credits, PaymentOrder.State state, String paymentUrl, boolean creditGranted,
        LocalDateTime createdAt) {
    static PaymentOrderResponse from(PaymentOrder o,Instant now) {
        var state=o.getState();
        if(state==PaymentOrder.State.REQUESTING && !o.getRequestDeadline().isAfter(now)) state=PaymentOrder.State.UNKNOWN;
        return new PaymentOrderResponse(o.getPublicId(),o.getProductId(),o.getProductName(),o.getPrice(),
                o.getCurrency(),o.getCredits(),state,state==PaymentOrder.State.READY?o.getPaymentUrl():null,
                o.getCreditGrantId()!=null,o.getCreatedAt());
    }
}
