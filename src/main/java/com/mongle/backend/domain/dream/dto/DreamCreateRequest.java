package com.mongle.backend.domain.dream.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record DreamCreateRequest(
        @NotNull(message = "꿈 날짜를 선택해주세요.") LocalDate dreamedAt,
        @NotNull(message = "꿈 원문을 입력해주세요.") String originalText) {}
