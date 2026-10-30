package com.mongle.backend.domain.dream.dto;

import com.mongle.backend.domain.dream.entity.DreamEmotion;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;

public record DreamEmotionsRequest(
        @NotNull(message = "현재 버전을 전달해주세요.") @PositiveOrZero(message = "버전은 0 이상이어야 합니다.")
                Long revision,
        @ArraySchema(
                arraySchema = @Schema(description = "기존 7종 중 중복 없이 1~2개. 대표 감정과 순위는 없습니다."),
                minItems = 1,
                maxItems = 2,
                uniqueItems = true)
        @NotNull(message = "감정을 선택해주세요.") List<DreamEmotion> emotions) {}
