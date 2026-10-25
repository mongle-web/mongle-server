package com.mongle.backend.domain.payment;

import com.mongle.backend.global.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PaymentErrorCode implements ErrorCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "PAYMENT_400_1", "상품과 주문 입력을 확인해주세요."),
    INVALID_PAGE(HttpStatus.BAD_REQUEST, "PAYMENT_400_2", "조회 개수는 1~50개이며 이전 주문 ID는 양수여야 합니다."),
    INVALID_FEEDBACK(HttpStatus.BAD_REQUEST, "PAYMENT_400_3", "결제 통보를 검증할 수 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "PAYMENT_404_1", "주문을 찾을 수 없습니다."),
    PRODUCT_UNAVAILABLE(HttpStatus.CONFLICT, "PAYMENT_409_1", "현재 구매할 수 없는 상품입니다."),
    REQUEST_CONFLICT(HttpStatus.CONFLICT, "PAYMENT_409_2", "같은 식별자로 다른 주문을 요청할 수 없습니다."),
    TRANSACTION_CONFLICT(HttpStatus.CONFLICT, "PAYMENT_409_3", "주문과 결제 거래가 일치하지 않습니다."),
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_503_1", "결제 설정이 준비되지 않았습니다."),
    CAPACITY_EXCEEDED(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_503_2", "결제 요청이 많습니다. 잠시 후 다시 시도해주세요.");
    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
