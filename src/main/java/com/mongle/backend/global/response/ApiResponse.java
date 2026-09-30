package com.mongle.backend.global.response;

import com.mongle.backend.global.error.ErrorCode;

import java.util.List;

public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data,
        List<FieldErrorDetail> errors
) {

    public ApiResponse {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(
                true,
                "SUCCESS",
                "요청에 성공했습니다.",
                data,
                List.of()
        );
    }

    public static ApiResponse<Void> emptySuccess() {
        return success(null);
    }

    public static ApiResponse<Void> failure(ErrorCode errorCode) {
        return failure(errorCode, List.of());
    }

    public static ApiResponse<Void> failure(
            ErrorCode errorCode,
            List<FieldErrorDetail> errors
    ) {
        return new ApiResponse<>(
                false,
                errorCode.getCode(),
                errorCode.getMessage(),
                null,
                errors
        );
    }
}