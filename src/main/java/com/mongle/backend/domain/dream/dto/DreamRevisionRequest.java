package com.mongle.backend.domain.dream.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record DreamRevisionRequest(
        @NotNull(message = "현재 버전을 전달해주세요.") @PositiveOrZero(message = "버전은 0 이상이어야 합니다.")
                Long revision) {}
