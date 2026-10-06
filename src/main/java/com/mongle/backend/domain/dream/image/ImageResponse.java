package com.mongle.backend.domain.dream.image;

import com.mongle.backend.global.common.GenerationStatus;

import java.time.LocalDate;

public record ImageResponse(
        Long imageId,
        Long analysisId,
        Long dreamId,
        LocalDate dreamedAt,
        long imageVersion,
        GenerationStatus status,
        String failureCode,
        String style,
        String mood,
        boolean sourceDeleted,
        boolean sourceChanged,
        boolean storyChanged,
        boolean hasPreviousResult,
        Long resultRevision,
        String resultStyle,
        String resultMood,
        String contentType,
        Integer width,
        Integer height) {}
