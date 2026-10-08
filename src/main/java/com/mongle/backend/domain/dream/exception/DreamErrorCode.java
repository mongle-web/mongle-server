package com.mongle.backend.domain.dream.exception;

import com.mongle.backend.global.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum DreamErrorCode implements ErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND, "DREAM_404_1", "꿈 기록을 찾을 수 없습니다."),
    DATE_REQUIRED(HttpStatus.BAD_REQUEST, "DREAM_400_1", "꿈 날짜를 선택해주세요."),
    FUTURE_DATE(HttpStatus.BAD_REQUEST, "DREAM_400_2", "미래 날짜에는 꿈을 기록할 수 없습니다."),
    DATE_OCCUPIED(
            HttpStatus.CONFLICT, "DREAM_409_1", "해당 날짜의 꿈 기록이 이미 있습니다. 기존 기록을 이어서 작성해주세요."),
    INVALID_TEXT(
            HttpStatus.BAD_REQUEST, "DREAM_400_3", "꿈 원문은 공백을 제외한 내용이 있어야 하며 최대 500자입니다."),
    INVALID_EMOTIONS(HttpStatus.BAD_REQUEST, "DREAM_400_4", "감정은 중복 없이 1~3개 선택해주세요."),
    // 이전 API 오류 코드의 식별자를 보존한다. 현재 감정 수정 정책에서는 발생하지 않는다.
    @Deprecated
    EMOTIONS_IMMUTABLE(
            HttpStatus.BAD_REQUEST, "DREAM_400_5", "완성한 꿈의 감정은 수정할 수 없습니다."),
    INVALID_TITLE(
            HttpStatus.BAD_REQUEST, "DREAM_400_6", "제목은 공백을 제외한 내용이 있어야 하며 최대 20자입니다."),
    INVALID_STATE(HttpStatus.CONFLICT, "DREAM_409_2", "현재 작성 단계에서는 이 작업을 할 수 없습니다."),
    VERSION_CONFLICT(
            HttpStatus.CONFLICT,
            "DREAM_409_3",
            "꿈 기록이 변경되었습니다. 최신 기록을 불러온 뒤 다시 저장해주세요."),
    EMPTY_UPDATE(HttpStatus.BAD_REQUEST, "DREAM_400_7", "수정할 항목을 전달해주세요.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
