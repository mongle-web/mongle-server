package com.mongle.backend.domain.ai.error;

import com.mongle.backend.global.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 외부 제공자에 관계없이 호출부가 식별할 AI 실패 유형.
 *
 * <p>HTTP 상태는 몽글 API에 노출할 상태이며 제공자의 상태를 그대로 복사하지 않는다.
 * 특히 제공자 인증 실패는 서버의 API 키 문제이므로 사용자 로그인 실패(401)가 아니다.
 * 안전한 고정 메시지만 사용자에게 노출하고 원본 응답/인증 정보는 메시지에 넣지 않는다.</p>
 *
 * <p>외부에 전달할 코드 형식은 {@code AI_HTTP상태_순번}이다. 순번은 같은 HTTP 상태 안에서
 * 부여하며, 이미 공개한 번호는 재정렬하거나 다른 오류에 재사용하지 않는다.</p>
 */
@Getter
@RequiredArgsConstructor
public enum AiGatewayErrorCode implements ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_GATEWAY, "AI_502_1", "AI 서비스에 전달한 요청을 처리할 수 없습니다.", false),
    AUTHENTICATION_FAILED(HttpStatus.BAD_GATEWAY, "AI_502_2", "AI 서비스 인증에 실패했습니다.", false),
    INSUFFICIENT_CREDIT(HttpStatus.SERVICE_UNAVAILABLE, "AI_503_1", "AI 서비스를 일시적으로 사용할 수 없습니다.", false),
    RATE_LIMITED(HttpStatus.SERVICE_UNAVAILABLE, "AI_503_2", "AI 서비스가 혼잡합니다. 잠시 후 다시 시도해주세요.", true),
    PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI_503_3", "AI 서비스를 일시적으로 사용할 수 없습니다.", true),
    TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "AI_504_1", "AI 서비스의 응답 시간이 초과되었습니다.", false),
    INVALID_RESPONSE(HttpStatus.BAD_GATEWAY, "AI_502_3", "AI 서비스의 응답을 처리할 수 없습니다.", false),
    INCOMPLETE_RESPONSE(HttpStatus.BAD_GATEWAY, "AI_502_4", "AI 서비스의 응답이 완성되지 않았습니다.", false);

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    // true인 유형도 제공자의 retryable=false 등 실제 맥락에 따라 재시도를 거절할 수 있다.
    // 타임아웃은 제공자가 이미 생성/과금을 마쳤을 수 있으므로 기본 재시도 후보에서 제외한다.
    private final boolean retryCandidate;
}
