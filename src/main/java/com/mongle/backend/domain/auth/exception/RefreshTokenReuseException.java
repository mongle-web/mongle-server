package com.mongle.backend.domain.auth.exception;

import com.mongle.backend.global.error.BusinessException;

// 이 예외로 반환하는 401은 세션 폐기를 롤백하지 않아야 한다.
public class RefreshTokenReuseException extends BusinessException {
    public RefreshTokenReuseException() {
        super(AuthErrorCode.INVALID_REFRESH_TOKEN);
    }
}
