package com.mongle.backend.domain.ai.dto.response;

import org.jspecify.annotations.Nullable;

/**
 * 제공자가 보고한 토큰 사용량. {@code null}은 미제공, {@code 0}은 보고된 실제 0을 뜻한다.
 *
 * <p>일부 필드만 제공되는 응답도 보존한다. 누락 값을 0으로 대체하거나 합계로 추정하지
 * 않는다. 캐시 토큰은 입력 토큰에, 추론 토큰은 출력 토큰에 포함되는 세부 수량이므로
 * 비용 계산 시 다시 더하면 중복 계산된다. 비용 계산은 후속 호출 로그 이슈에서 구현한다.</p>
 *
 * @param inputTokens 전체 입력 토큰 수
 * @param outputTokens 전체 출력 토큰 수. 제공자가 포함한 추론 토큰도 이 수에 포함된다.
 * @param totalTokens 제공자가 보고한 전체 합계
 * @param cachedInputTokens 입력 토큰 중 캐시에서 읽은 수
 * @param reasoningTokens 출력 토큰 중 추론에 사용한 수
 */
public record AiTokenUsage(
        @Nullable Integer inputTokens,
        @Nullable Integer outputTokens,
        @Nullable Integer totalTokens,
        @Nullable Integer cachedInputTokens,
        @Nullable Integer reasoningTokens
) {

    public AiTokenUsage {
        requireNonNegative(inputTokens, "입력 토큰 수");
        requireNonNegative(outputTokens, "출력 토큰 수");
        requireNonNegative(totalTokens, "전체 토큰 수");
        requireNonNegative(cachedInputTokens, "캐시 입력 토큰 수");
        requireNonNegative(reasoningTokens, "추론 토큰 수");
        if (inputTokens != null && cachedInputTokens != null && cachedInputTokens > inputTokens) {
            throw new IllegalArgumentException("캐시 입력 토큰 수는 전체 입력 토큰 수를 초과할 수 없습니다.");
        }
        if (outputTokens != null && reasoningTokens != null && reasoningTokens > outputTokens) {
            throw new IllegalArgumentException("추론 토큰 수는 전체 출력 토큰 수를 초과할 수 없습니다.");
        }
        if (inputTokens != null && outputTokens != null && totalTokens != null
                && (long) inputTokens + outputTokens != totalTokens) {
            throw new IllegalArgumentException("전체 토큰 수는 입력 토큰 수와 출력 토큰 수의 합과 같아야 합니다.");
        }
    }

    /** usage 전체가 누락되어도 응답의 사용량 객체는 null 대신 이 값으로 통일한다. */
    public static AiTokenUsage unknown() {
        return new AiTokenUsage(null, null, null, null, null);
    }

    private static void requireNonNegative(@Nullable Integer value, String field) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(field + "는 음수일 수 없습니다.");
        }
    }
}
