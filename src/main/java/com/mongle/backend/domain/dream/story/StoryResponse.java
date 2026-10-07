package com.mongle.backend.domain.dream.story;

import com.mongle.backend.global.common.GenerationStatus;

import java.time.LocalDate;
import java.util.List;

public record StoryResponse(
        Long storyId,
        Long analysisId,
        Long dreamId,
        LocalDate dreamedAt,
        long sourceRevision,
        long storyVersion,
        GenerationStatus status,
        String failureCode,
        boolean sourceDeleted,
        boolean sourceChanged,
        boolean hasPreviousResult,
        Long resultRevision,
        String resultPromptVersion,
        List<StoryResult.Section> sections) {}
