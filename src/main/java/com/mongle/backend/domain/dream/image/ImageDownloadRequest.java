package com.mongle.backend.domain.dream.image;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.*;

import java.util.List;

@Schema(name = "BatchImageDownloadRequest")
public record ImageDownloadRequest(
        @ArraySchema(
                        arraySchema =
                                @Schema(description = "중복 제거 전 1~31개. 중복은 최초 순서로 한 번만 처리합니다."),
                        minItems = 1,
                        maxItems = 31,
                        schema = @Schema(type = "integer", format = "int64", minimum = "1"))
                @NotNull
                @Size(min = 1, max = 31)
                List<@NotNull @Positive Long> imageIds) {}
