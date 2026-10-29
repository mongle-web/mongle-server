package com.mongle.backend.domain.world.summary;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

public record WorldSummaryResponse(
        Long worldId,
        long compositionRevision,
        Long resultCompositionRevision,
        long generationVersion,
        Status status,
        String failureCode,
        String generatedTitle,
        List<WorldSummaryContent.Keyword> commonKeywords,
        List<WorldSummaryContent.Emotion> recurringEmotions,
        int sourceCount,
        boolean limitedEvidence,
        boolean sourceChanged,
        boolean refreshNeeded,
        boolean hasPreviousResult,
        Instant generatedAt,
        String promptVersion) {
    @Schema(
            description =
                    "EMPTY: 출처 없음, NOT_GENERATED: 미요청, PENDING: 대기, PROCESSING: 생성 중, COMPLETED:"
                        + " 완료, FAILED: 실패")
    public enum Status {
        EMPTY,
        NOT_GENERATED,
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED
    }
}
