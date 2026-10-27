package com.mongle.backend.domain.world.image;

import com.mongle.backend.global.common.GenerationStatus;

public record WorldImageResponse(Long imageId, Long worldId, long imageVersion, GenerationStatus status,
        String failureCode, long compositionRevision, Long resultCompositionRevision,
        boolean compositionChanged, boolean sourceChanged, boolean sourceDeleted, boolean hasPreviousResult,
        String style, String mood, String resultStyle, String resultMood,
        String contentType, Integer width, Integer height) {}
