package com.mongle.backend.domain.dream.interpretation;

import com.mongle.backend.global.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum InterpretationErrorCode implements ErrorCode {
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "INTERPRETATION_503_1", "종합 해석 생성이 준비되지 않았습니다."),
    CONTEXT_TOO_LARGE(
            HttpStatus.BAD_REQUEST, "INTERPRETATION_400_1", "종합 해석 대상이 처리 가능한 길이를 초과했습니다."),
    INVALID_OUTPUT(HttpStatus.BAD_GATEWAY, "INTERPRETATION_502_1", "종합 해석 응답 형식이 올바르지 않습니다."),
    CALL_FAILED(HttpStatus.BAD_GATEWAY, "INTERPRETATION_502_2", "종합 해석 저장에 실패했습니다. 다시 시도해주세요.");
    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
