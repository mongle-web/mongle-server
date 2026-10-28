package com.mongle.backend.domain.dream.interpretation;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.*;
import java.util.List;

public record InterpretationResponse(
        State status, String failureCode, String interpretation, Instant generatedAt,
        String promptVersion, boolean refreshNeeded, boolean limitedEvidence,
        LocalDate from, LocalDate to, int targetCount,
        @Schema(description = "완성본을 생성할 때 사용한 분석 목록. 현재 대상과 다르면 refreshNeeded=true")
        List<InterpretationTargets.Source> sources) {
    public enum State { EMPTY, NOT_GENERATED, PROCESSING, COMPLETED, FAILED }
    @Override public String toString() { return "InterpretationResponse[status=" + status + "]"; }
}
