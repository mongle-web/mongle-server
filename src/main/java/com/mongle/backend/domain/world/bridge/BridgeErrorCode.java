package com.mongle.backend.domain.world.bridge;

import com.mongle.backend.global.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum BridgeErrorCode implements ErrorCode {
    INVALID_PAIR(HttpStatus.BAD_REQUEST, "BRIDGE_400_1", "서로 다른 두 꿈의 성공한 서사 버전이 필요합니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "BRIDGE_404_1", "연결 결과 또는 서사 버전을 찾을 수 없습니다."),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "BRIDGE_409_1", "최신 연결 작업 버전으로 다시 요청해주세요."),
    SETTINGS_CHANGED(HttpStatus.CONFLICT, "BRIDGE_409_2", "생성 설정이 변경되었습니다. 두 서사 버전으로 새 연결 요청을 해주세요."),
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "BRIDGE_503_1", "AI 연결이 준비되지 않았습니다."),
    INVALID_OUTPUT(HttpStatus.BAD_GATEWAY, "BRIDGE_502_1", "AI 연결 문장 형식이 올바르지 않습니다."),
    CALL_FAILED(HttpStatus.BAD_GATEWAY, "BRIDGE_502_2", "연결 문장 저장에 실패했습니다. 상태를 확인해주세요.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
