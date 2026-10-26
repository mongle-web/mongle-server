package com.mongle.backend.domain.credit;

import com.mongle.backend.global.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CreditErrorCode implements ErrorCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "CREDIT_400_1", "크레딧 수량과 요청 식별자를 확인해주세요."),
    INVALID_PAGE(HttpStatus.BAD_REQUEST, "CREDIT_400_2", "조회 개수는 1~50개이며 이전 내역 ID는 양수여야 합니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "CREDIT_404_1", "크레딧 요청을 찾을 수 없습니다."),
    INSUFFICIENT(HttpStatus.CONFLICT, "CREDIT_409_1", "사용 가능한 크레딧이 부족합니다."),
    REQUEST_CONFLICT(HttpStatus.CONFLICT, "CREDIT_409_2", "같은 식별자로 다른 크레딧 요청을 처리할 수 없습니다."),
    STATE_CONFLICT(HttpStatus.CONFLICT, "CREDIT_409_3", "현재 크레딧 예약 상태에서는 처리할 수 없습니다."),
    CAPACITY_EXCEEDED(HttpStatus.CONFLICT, "CREDIT_409_4", "처리 가능한 크레딧 잔액 범위를 초과했습니다."),
    PAYMENT_REVIEW(HttpStatus.CONFLICT, "CREDIT_409_5", "결제 취소 내역 확인 중으로 크레딧 사용이 보류되었습니다."),
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "CREDIT_503_1", "크레딧 사용 정책이 아직 준비되지 않았습니다.");
    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
