package com.mongle.backend.domain.world.composition;

import com.mongle.backend.global.error.ErrorCode;

import lombok.*;

import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum WorldCompositionErrorCode implements ErrorCode {
    INVALID_PAGE(HttpStatus.BAD_REQUEST, "COMPOSITION_400_1", "세계관 조회 개수와 커서를 확인해주세요."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "COMPOSITION_404_1", "세계관 구성 또는 출처를 찾을 수 없습니다."),
    CONFLICT(HttpStatus.CONFLICT, "COMPOSITION_409_1", "세계관 또는 폴더가 변경되었습니다. 새로 조회해주세요."),
    SOURCES_REQUIRED(HttpStatus.CONFLICT, "COMPOSITION_409_2", "최초 세계관 생성에는 꿈이 2개 이상 필요합니다."),
    SOURCE_NOT_READY(HttpStatus.CONFLICT, "COMPOSITION_409_3", "현재 원문과 감정으로 완료한 서사가 필요합니다."),
    BUSY(HttpStatus.CONFLICT, "COMPOSITION_409_4", "다른 세계관 생성 또는 반영 작업이 진행 중입니다."),
    RETRY_REQUIRED(HttpStatus.CONFLICT, "COMPOSITION_409_5", "실패한 작업을 다시 조회한 뒤 재시도해주세요."),
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "COMPOSITION_503_1", "세계관 생성 기능이 준비되지 않았습니다.");
    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
