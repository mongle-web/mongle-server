package com.mongle.backend.domain.user.exception;

import com.mongle.backend.domain.user.entity.NicknamePolicy;
import com.mongle.backend.global.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {

    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."),
    INVALID_NICKNAME(HttpStatus.BAD_REQUEST, "USER_INVALID_NICKNAME", NicknamePolicy.MESSAGE),
    ONBOARDING_ALREADY_COMPLETED(HttpStatus.CONFLICT, "USER_ONBOARDING_ALREADY_COMPLETED",
            "이미 온보딩을 완료했습니다."),
    ONBOARDING_REQUIRED(HttpStatus.FORBIDDEN, "USER_ONBOARDING_REQUIRED",
            "닉네임 입력을 먼저 완료해주세요.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
