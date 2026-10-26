package com.mongle.backend.domain.dream.image;

import com.mongle.backend.global.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ImageErrorCode implements ErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND, "IMAGE_404_1", "이미지 결과를 찾을 수 없습니다."),
    STORY_REQUIRED(HttpStatus.CONFLICT, "IMAGE_409_1", "최신 원문으로 완료한 이야기가 필요합니다."),
    VERSION_CONFLICT(
            HttpStatus.CONFLICT, "IMAGE_409_2", "이미지 상태가 변경되었습니다. 최신 결과를 조회해주세요."),
    REGENERATION_REQUIRED(
            HttpStatus.CONFLICT, "IMAGE_409_3", "이야기나 옵션을 변경하려면 재생성을 요청해주세요."),
    INVALID_OPTION(HttpStatus.BAD_REQUEST, "IMAGE_400_1", "허용된 이미지 스타일과 분위기를 선택해주세요."),
    INVALID_OUTPUT(HttpStatus.BAD_GATEWAY, "IMAGE_502_1", "이미지 생성 결과가 올바르지 않습니다."),
    CALL_FAILED(HttpStatus.BAD_GATEWAY, "IMAGE_502_2", "이미지 처리 중 오류가 발생했습니다."),
    BUSY(HttpStatus.SERVICE_UNAVAILABLE, "IMAGE_503_2", "이미지 처리 한도에 도달했습니다. 잠시 후 다시 시도해주세요."),
    UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE, "IMAGE_503_1", "이미지 생성 또는 저장소 연결이 준비되지 않았습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
