package com.mongle.backend.domain.dream.generation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** 조회한 두 버전을 전달하여 완료된 요청의 재전송으로 새 비용이 발생하지 않게 한다. */
public record DreamRegenerationRequest(
        @NotNull @PositiveOrZero Long revision,
        @NotNull @PositiveOrZero Long generationVersion) {}
