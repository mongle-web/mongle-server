package com.mongle.backend.domain.ai.dto.response;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Gateway가 도메인에 반환하는 생성 내용과 호출 메타데이터.
 *
 * <p>텍스트/JSON 내용은 원문 그대로 전달한다. 이 DTO 생성 자체는 성공 판정이 아니다.
 * 실제 어댑터는 빈 응답이나 잘린 응답을 공통 예외로 처리해야 하고, 도메인은 반환된
 * JSON을 자신의 DTO로 읽어 업무 규칙을 검증한 뒤 저장한다.</p>
 *
 * @param content 비어 있지 않은 생성 내용. 공백 정리나 JSON 역직렬화를 하지 않는다.
 * @param modelName 제공자가 반환한 모델명. 누락되면 null이며 요청 모델로 채우지 않는다.
 *                  LINER의 공개 모델명은 내부에서 라우팅된 실제 모델명을 의미하지 않는다.
 * @param usage 제공된 토큰 수. 전체 미제공이면 {@link AiTokenUsage#unknown()} 사용
 * @param finishReason 생성 종료 사유. 미제공이면 {@link AiFinishReason#unknown()} 사용
 * @param requestId 제공자의 추적용 요청 ID. 어댑터가 헤더 등에서 읽으며 미제공이면 null
 * @param latencyMs Gateway 호출 시작부터 반환까지 측정한 0 이상의 밀리초. 재시도가 있다면 합산 시간
 */
public record AiGenerationResult(
        String content,
        @Nullable String modelName,
        AiTokenUsage usage,
        AiFinishReason finishReason,
        @Nullable String requestId,
        long latencyMs
) {

    public AiGenerationResult {
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(usage, "usage must not be null");
        Objects.requireNonNull(finishReason, "finishReason must not be null");
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        if (modelName != null && modelName.isBlank()) {
            throw new IllegalArgumentException("modelName must not be blank when present");
        }
        if (requestId != null && requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank when present");
        }
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs must not be negative");
        }
    }
}
