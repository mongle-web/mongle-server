package com.mongle.backend.domain.dream.generation;

public record DreamGenerationResponse(
        Long dreamId,
        long sourceRevision,
        boolean sourceChanged,
        DreamGenerationJob.Stage stage,
        DreamGenerationJob.Status status,
        String failureCode,
        Long analysisId,
        Long storyId) {}
