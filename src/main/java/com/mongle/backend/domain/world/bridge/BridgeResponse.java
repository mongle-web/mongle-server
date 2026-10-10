package com.mongle.backend.domain.world.bridge;

import com.mongle.backend.domain.dream.gateway.DreamGenerationSettings;
import com.mongle.backend.global.common.GenerationStatus;

import java.time.Instant;
import java.time.LocalDate;

/** jobVersion은 재시도 충돌 검사값이다. 세계관 출처는 성공한 bridgeId와 두 storyVersionId다. */
public record BridgeResponse(
        Long bridgeId,
        Long beforeDreamId,
        Long afterDreamId,
        Long beforeStoryVersionId,
        Long afterStoryVersionId,
        LocalDate beforeDreamedAt,
        LocalDate afterDreamedAt,
        String promptVersion,
        DreamGenerationSettings.Request settings,
        GenerationStatus status,
        String failureCode,
        String content,
        long jobVersion,
        Instant leaseUntil) {}
