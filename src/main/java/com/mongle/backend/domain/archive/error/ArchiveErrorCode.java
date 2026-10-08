package com.mongle.backend.domain.archive.error;

import com.mongle.backend.global.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** Archive 조회 계약의 오류. 타인·초안·삭제된 기록은 같은 404로 처리한다. */
@Getter
@RequiredArgsConstructor
public enum ArchiveErrorCode implements ErrorCode {
    INVALID_FILTER(HttpStatus.BAD_REQUEST, "ARCHIVE_400_1", "월 또는 날짜를 올바른 형식으로 하나만 전달해주세요."),
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, "ARCHIVE_400_2", "조회 커서가 올바르지 않습니다. 같은 조회 조건으로 다시 요청해주세요."),
    INVALID_SIZE(HttpStatus.BAD_REQUEST, "ARCHIVE_400_3", "조회 개수는 1~50개로 지정해주세요."),
    INVALID_SORT(HttpStatus.BAD_REQUEST, "ARCHIVE_400_4", "정렬은 LATEST 또는 OLDEST로 지정해주세요."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "ARCHIVE_404_1", "꿈 기록을 찾을 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
