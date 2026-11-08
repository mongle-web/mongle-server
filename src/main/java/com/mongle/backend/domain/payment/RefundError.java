package com.mongle.backend.domain.payment;

import com.mongle.backend.global.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
enum RefundError implements ErrorCode {
    INVALID(HttpStatus.BAD_REQUEST, "REFUND_400_1", "환불 신청 정보를 확인해주세요."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "REFUND_404_1", "환불 대상이나 접수 내역을 찾을 수 없습니다."),
    CONFLICT(HttpStatus.CONFLICT, "REFUND_409_1", "이미 접수된 환불이나 다른 요청에 사용된 식별자입니다."),
    INELIGIBLE(HttpStatus.CONFLICT, "REFUND_409_2", "현재 이 주문의 환불 신청을 접수할 수 없습니다."),
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "REFUND_503_1", "환불 신청 서비스가 준비되지 않았습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
