package com.mongle.backend.domain.world.image;

import jakarta.validation.constraints.*;

public record WorldImageRequest(
        @NotNull @PositiveOrZero Long compositionRevision,
        @NotBlank @Size(max = 50) String style,
        @Size(max = 50) String mood,
        Boolean regenerate,
        @PositiveOrZero Long imageVersion) {
    public WorldImageRequest { regenerate = Boolean.TRUE.equals(regenerate); }
    @AssertTrue(message = "재생성 시 현재 이미지 버전을 전달해주세요.")
    public boolean isRegenerationVersionProvided() { return !regenerate || imageVersion != null; }
}
