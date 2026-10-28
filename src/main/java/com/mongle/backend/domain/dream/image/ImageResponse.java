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
        Integer height,
        @io.swagger.v3.oas.annotations.media.Schema(description = "성공 결과가 있고 무료 재생성을 아직 사용하지 않았는지. 처리 중에는 새 요청 대신 진행 중 결과를 조회합니다.")
        boolean freeRegenerationAvailable,
        @io.swagger.v3.oas.annotations.media.Schema(description = "현재 이력 기준 다음 재생성 비용. 무료 0, 성공 결과나 가격 설정이 없으면 null. 기능 활성화·처리 중·입력 변경 여부는 별도로 확인합니다.", nullable = true)
        Long nextRegenerationCost) {}
