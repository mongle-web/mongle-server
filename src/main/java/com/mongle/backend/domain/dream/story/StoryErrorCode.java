package com.mongle.backend.domain.dream.story;

import com.mongle.backend.global.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum StoryErrorCode implements ErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND, "STORY_NOT_FOUND", "이야기를 찾을 수 없습니다."),
    ANALYSIS_REQUIRED(HttpStatus.CONFLICT, "STORY_ANALYSIS_REQUIRED", "장면 분석이 완료되어야 합니다."),
    ANALYSIS_STALE(HttpStatus.CONFLICT, "STORY_ANALYSIS_STALE", "현재 원문과 장면 분석의 버전이 다릅니다."),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "STORY_VERSION_CONFLICT", "최신 이야기 버전으로 다시 요청해주세요."),
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "STORY_UNAVAILABLE", "AI 연결이 준비되지 않았습니다."),
    INVALID_OUTPUT(HttpStatus.BAD_GATEWAY, "STORY_INVALID_OUTPUT", "AI 응답 형식이 올바르지 않습니다."),
    CALL_FAILED(HttpStatus.BAD_GATEWAY, "STORY_CALL_FAILED", "이야기 생성에 실패했습니다. 다시 시도해주세요.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
