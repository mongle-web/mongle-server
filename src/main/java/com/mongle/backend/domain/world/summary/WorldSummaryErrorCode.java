package com.mongle.backend.domain.world.summary;

import com.mongle.backend.global.error.ErrorCode;

import lombok.*;

import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum WorldSummaryErrorCode implements ErrorCode {
    TOO_LARGE(HttpStatus.BAD_REQUEST, "WORLD_SUMMARY_400_1", "세계관 결과 생성 입력이 제한을 초과했습니다."),
    REQUIRED(HttpStatus.CONFLICT, "WORLD_SUMMARY_409_1", "먼저 세계관 완성본을 만들어주세요."),
    BUSY(HttpStatus.CONFLICT, "WORLD_SUMMARY_409_2", "세계관 구성을 반영 중입니다."),
    INPUT_UNAVAILABLE(HttpStatus.CONFLICT, "WORLD_SUMMARY_409_3", "세계관 결과의 출처를 확인해주세요."),
    INVALID_OUTPUT(HttpStatus.BAD_GATEWAY, "WORLD_SUMMARY_502_1", "세계관 제목 생성 결과를 확인할 수 없습니다."),
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "WORLD_SUMMARY_503_1", "세계관 결과 생성이 준비되지 않았습니다.");
    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
