package com.mongle.backend.domain.dream.analysis;

import com.mongle.backend.global.common.GenerationStatus;
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
        List<StructureResult.Scene> scenes,
        List<StructureResult.Element> elements) {}
