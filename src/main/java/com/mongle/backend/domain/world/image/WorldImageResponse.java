package com.mongle.backend.domain.world.image;

import com.mongle.backend.global.common.GenerationStatus;

public record WorldImageResponse(Long imageId, Long worldId, long imageVersion, GenerationStatus status,
        String failureCode, long compositionRevision, Long resultCompositionRevision,
        boolean compositionChanged, boolean sourceChanged, boolean sourceDeleted, boolean hasPreviousResult,
        String style, String mood, String resultStyle, String resultMood,
        String contentType, Integer width, Integer height,
        @io.swagger.v3.oas.annotations.media.Schema(description = "성공 결과가 있고 무료 재생성을 아직 사용하지 않았는지. 처리 중에는 진행 중 결과를 조회합니다.")
        boolean freeRegenerationAvailable,
        @io.swagger.v3.oas.annotations.media.Schema(description = "현재 이력 기준 다음 재생성 비용. 무료 0, 성공 결과나 가격 설정이 없으면 null. 실제 생성 가능 여부와 별개입니다.", nullable = true)
        Long nextRegenerationCost) {}
