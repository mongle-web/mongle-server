package com.mongle.backend.domain.auth.exception;

import com.mongle.backend.global.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {
    UNSUPPORTED_PROVIDER(HttpStatus.BAD_REQUEST, "AUTH_UNSUPPORTED_PROVIDER", "지원하지 않는 로그인 방식입니다."),
    INVALID_PROVIDER_RESPONSE(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_PROVIDER_RESPONSE", "소셜 계정 정보를 확인할 수 없습니다."),
    EMAIL_REQUIRED(HttpStatus.UNAUTHORIZED, "AUTH_EMAIL_REQUIRED", "이메일 제공에 동의해주세요."),
    EMAIL_NOT_VERIFIED(HttpStatus.UNAUTHORIZED, "AUTH_EMAIL_NOT_VERIFIED", "인증된 이메일이 필요합니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_TOKEN", "인증 토큰이 만료되었거나 올바르지 않습니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_REFRESH_TOKEN", "로그인이 만료되었습니다. 다시 로그인해주세요."),
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "AUTH_LOGIN_FAILED", "소셜 로그인에 실패했습니다. 다시 시도해주세요."),
    LOGIN_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_LOGIN_UNAVAILABLE", "로그인을 처리할 수 없습니다. 잠시 후 다시 시도해주세요.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
