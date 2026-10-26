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
        @io.swagger.v3.oas.annotations.media.Schema(description = "실패 시 원인 코드. ATTEMPT_EXPIRED, CREDIT_RESERVATION_EXPIRED, CREDIT_RESERVATION_MISSING, CREDIT_PAYMENT_REVIEW 등을 포함. 실패해도 이전 성공 결과는 유지될 수 있습니다.")
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
