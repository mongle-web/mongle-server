package com.mongle.backend.domain.dream.image;

import jakarta.validation.constraints.*;

public record ImageRequest(
        @NotNull(message = "현재 꿈 버전을 전달해주세요.") @PositiveOrZero Long revision,
        @NotBlank(message = "스타일을 선택해주세요.") @Size(max = 50) String style,
        @Size(max = 50) String mood,
        Boolean regenerate,
        @PositiveOrZero Long imageVersion) {
    public ImageRequest {
        regenerate = Boolean.TRUE.equals(regenerate);
    }

    @AssertTrue(message = "재생성 시 현재 이미지 버전을 전달해주세요.")
    public boolean isRegenerationVersionProvided() {
        return !regenerate || imageVersion != null;
    }
}
