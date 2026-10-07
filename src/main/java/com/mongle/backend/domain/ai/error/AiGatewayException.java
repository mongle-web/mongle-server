package com.mongle.backend.domain.ai.error;

import com.mongle.backend.global.error.BusinessException;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Objects;

/**
 * 외부 호출 실패를 공통 오류 코드와 제한된 메타데이터로 전달하는 예외.
 *
 * <p>기존 BusinessException을 상속하므로 GlobalExceptionHandler의 공통 응답 처리를 사용한다.
 * 재시도 가능 여부는 힌트이며 이 예외 자체가 재시도를 실행하지 않는다. 실제 재시도 횟수,
 * 전체 시간 제한과 대기 정책은 후속 제공자 어댑터에서 정한다.</p>
 */
@Getter
public final class AiGatewayException extends BusinessException {

    /** 오류 응답에서도 제공자가 알려준 추적 ID를 보존하며, 알 수 없으면 null이다. */
    private final @Nullable String requestId;

    /** 오류 유형과 제공자의 판단이 모두 허용하는 경우에만 true가 된다. */
    private final boolean retryable;

    /** 제공자가 요구한 대기 시간. 미제공이면 null이며 실제 대기는 어댑터가 수행한다. */
    private final @Nullable Duration retryAfter;

    /**
     * @param errorCode 외부 실패의 공통 분류
     * @param requestId 제공자의 요청 ID 또는 null
     * @param retryable 실제 응답 맥락에서 재시도가 허용되는지. 비후보 유형이면 false로 제한한다.
     * @param retryAfter 제공자가 반환한 0 이상의 대기 시간 또는 null
     * @param cause 내부 진단용 원인 또는 null. 사용자 응답에는 고정 오류 메시지만 사용한다.
     */
    public AiGatewayException(
            AiGatewayErrorCode errorCode,
            @Nullable String requestId,
            boolean retryable,
            @Nullable Duration retryAfter,
            @Nullable Throwable cause
    ) {
        super(Objects.requireNonNull(errorCode, "errorCode must not be null"), cause);
        if (requestId != null && requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank when present");
        }
        if (retryAfter != null && retryAfter.isNegative()) {
            throw new IllegalArgumentException("retryAfter must not be negative");
        }
        this.requestId = requestId;
        this.retryable = errorCode.isRetryCandidate() && retryable;
        this.retryAfter = retryAfter;
    }
}
