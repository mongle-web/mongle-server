package com.mongle.backend.domain.world.folder;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record WorldFolderUpdateRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 100,
                description = "공백만 입력 불가. 최대 100 Unicode 코드포인트.") String name,
        @NotNull @PositiveOrZero @Schema(description = "최신 폴더 revision", example = "0") Long revision) {}
