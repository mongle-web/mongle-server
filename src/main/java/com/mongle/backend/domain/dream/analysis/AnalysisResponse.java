package com.mongle.backend.domain.dream.analysis;

import com.mongle.backend.global.common.GenerationStatus;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

public record AnalysisResponse(
        Long analysisId,
        Long dreamId,
        LocalDate dreamedAt,
        long sourceRevision,
        GenerationStatus status,
        String failureCode,
        boolean sourceDeleted,
        boolean sourceChanged,
        @Schema(description = "현재 꿈의 수정용 revision. 자동 제목 저장 후 증가할 수 있으며 삭제된 원문은 null")
                Long dreamRevision,
        @Schema(description = "분석 생성 시 제목 스냅샷. 현재 화면 제목은 DreamResponse.title 사용, 기존 분석은 null")
                String generatedTitle,
        @Schema(description = "중요한 순서대로 1~5개, 각 20 코드포인트 이내. 기존 분석은 빈 목록")
                List<String> displayKeywords,
        List<StructureResult.Scene> scenes,
        List<StructureResult.Element> elements) {}
