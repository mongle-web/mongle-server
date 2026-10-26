package com.mongle.backend.domain.payment;
import io.swagger.v3.oas.annotations.media.Schema;
@Schema(description = "서버에 설정된 판매 상품. price는 원화 최종 결제금액이며 credits는 무기한 지급 수량입니다.")
public record CreditProduct(String productId, String name, long price, String currency, long credits) {
    static CreditProduct from(PaymentProperties.Product p) {
        return new CreditProduct(p.id(), p.name(), p.price(), p.currency(), p.credits());
    }
}
