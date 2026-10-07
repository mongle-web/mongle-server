package com.mongle.backend.domain.dream.analysis;

import com.mongle.backend.global.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AnalysisErrorCode implements ErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND, "ANALYSIS_404_1", "분석을 찾을 수 없습니다."),
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "ANALYSIS_503_1", "AI 연결이 준비되지 않았습니다."),
    INVALID_OUTPUT(HttpStatus.BAD_GATEWAY, "ANALYSIS_502_1", "AI 응답 형식이 올바르지 않습니다."),
    CALL_FAILED(HttpStatus.BAD_GATEWAY, "ANALYSIS_502_2", "AI 분석에 실패했습니다. 다시 시도해주세요.");
    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
