package com.mongle.backend.domain.user.exception;

import com.mongle.backend.domain.user.entity.NicknamePolicy;
import com.mongle.backend.global.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {

    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_404_1", "사용자를 찾을 수 없습니다."),
    INVALID_NICKNAME(HttpStatus.BAD_REQUEST, "USER_400_1", NicknamePolicy.MESSAGE),
    ONBOARDING_ALREADY_COMPLETED(HttpStatus.CONFLICT, "USER_409_1",
            "이미 온보딩을 완료했습니다."),
    ONBOARDING_REQUIRED(HttpStatus.FORBIDDEN, "USER_403_1",
            "닉네임 입력을 먼저 완료해주세요."),
    WITHDRAWAL_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "USER_503_1", "회원탈퇴 기능이 준비되지 않았습니다."),
    REFUND_REQUIRED(HttpStatus.CONFLICT, "USER_409_2", "남은 유료 크레딧에 대한 환불을 먼저 신청해주세요."),
    PAYMENT_UNCONFIRMED(HttpStatus.CONFLICT, "USER_409_3", "결제 승인 여부를 확인한 후 탈퇴할 수 있습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
