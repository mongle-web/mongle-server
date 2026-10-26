package com.mongle.backend.domain.world.folder;

import io.swagger.v3.oas.annotations.media.Schema;

public record WorldFolderRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 100,
                description = "공백만 입력 불가. 최대 100 Unicode 코드포인트. 입력한 앞뒤 공백은 보존합니다.", example = "바다의 세계")
        String name) {}
