package com.mongle.backend.domain.dream.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Positive;

public record DreamDraftRequest(
        @NotNull(message = "꿈 원문을 전달해주세요.") String originalText,
        @PositiveOrZero(message = "버전은 0 이상이어야 합니다.") Long revision,
        @Positive(message = "꿈 ID는 양수여야 합니다.") Long dreamId) {}
