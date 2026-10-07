package com.mongle.backend.domain.ai.dto.response;

import org.jspecify.annotations.Nullable;

/**
 * 생성 종료 사유의 원본 값과 공통 분류를 함께 제공한다.
 *
 * <p>제공자에 새 값이 추가되어도 응답 파싱을 실패시키지 않도록 원본 문자열을 보존한다.
 * 알려진 값의 분류는 호환 계약이며, 제공자마다 모든 값을 반환한다는 보장은 아니다.
 * STOP도 생성 종료만 의미하고 도메인 결과의 정확성이나 JSON 유효성을 보장하지 않는다.</p>
 *
 * @param providerValue 제공자가 반환한 종료 사유. 누락되면 {@code null}이며 STOP으로 추정하지 않는다.
 */
public record AiFinishReason(@Nullable String providerValue) {

    public AiFinishReason {
        if (providerValue != null && providerValue.isBlank()) {
            throw new IllegalArgumentException("종료 사유 원본 값은 제공된 경우 비어 있을 수 없습니다.");
        }
    }

    /** 원본 문자열을 유지하면서 도메인이 비교할 수 있는 분류를 제공한다. */
    public Type type() {
        return switch (providerValue) {
            case null -> Type.UNKNOWN;
            case "stop" -> Type.STOP;
            case "length" -> Type.OUTPUT_LIMIT;
            case "tool_calls" -> Type.TOOL_CALLS;
            case "content_filter" -> Type.CONTENT_FILTER;
            default -> Type.OTHER;
        };
    }

    public static AiFinishReason unknown() {
        return new AiFinishReason(null);
    }

    /** OTHER는 새로운 원본 값이 있는 경우, UNKNOWN은 값 자체가 없는 경우다. */
    public enum Type {
        STOP,
        OUTPUT_LIMIT,
        TOOL_CALLS,
        CONTENT_FILTER,
        OTHER,
        UNKNOWN
    }
}
