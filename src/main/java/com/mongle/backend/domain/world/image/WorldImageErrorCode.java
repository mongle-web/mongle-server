package com.mongle.backend.domain.world.image;

import com.mongle.backend.global.error.ErrorCode;
import lombok.*;
import org.springframework.http.HttpStatus;

@Getter @RequiredArgsConstructor
public enum WorldImageErrorCode implements ErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND,"WORLD_IMAGE_404_1","세계관 이미지 결과를 찾을 수 없습니다."),
    COMPOSITION_REQUIRED(HttpStatus.CONFLICT,"WORLD_IMAGE_409_1","꿈이 한 개 이상 있는 세계관 완성본이 필요합니다."),
    VERSION_CONFLICT(HttpStatus.CONFLICT,"WORLD_IMAGE_409_2","세계관 또는 이미지 버전이 변경되었습니다. 다시 조회해주세요."),
    REGENERATION_REQUIRED(HttpStatus.CONFLICT,"WORLD_IMAGE_409_3","구성이나 옵션을 변경하려면 이미지 재생성을 요청해주세요."),
    COMPOSITION_BUSY(HttpStatus.CONFLICT,"WORLD_IMAGE_409_4","세계관 생성·부분 반영이 진행 중입니다."),
    INPUT_TOO_LARGE(HttpStatus.BAD_REQUEST,"WORLD_IMAGE_400_1","세계관 이미지 입력이 허용된 크기를 초과했습니다.");
    private final HttpStatus httpStatus; private final String code; private final String message;
}
