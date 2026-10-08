package com.mongle.backend.domain.ai.liner.dto;

import com.mongle.backend.domain.ai.dto.response.AiTokenUsage;
import org.jspecify.annotations.Nullable;

/** 응답이 생성 결과로 쓸 수 없어도 로그에 남길 수 있는 메타데이터만 분리한다. */
public record LinerResponseMetadata(@Nullable String modelName, AiTokenUsage usage,
                                    @Nullable String finishReason, @Nullable String requestId) {
    public static LinerResponseMetadata unknown() {
        // 응답 전체를 받지 못한 통신 실패에는 사용량을 0으로 추정하지 않는다.
        return new LinerResponseMetadata(null, AiTokenUsage.unknown(), null, null);
    }
}
