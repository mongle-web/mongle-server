package com.mongle.backend.domain.world.folder;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record WorldRevisionRequest(
        @NotNull @PositiveOrZero @Schema(description = "최신 폴더 revision", example = "0") Long revision) {}
