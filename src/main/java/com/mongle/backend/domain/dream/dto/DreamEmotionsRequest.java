package com.mongle.backend.domain.dream.dto;

import com.mongle.backend.domain.dream.entity.DreamEmotion;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;

public record DreamEmotionsRequest(
        @NotNull(message = "현재 버전을 전달해주세요.")
        @PositiveOrZero(message = "버전은 0 이상이어야 합니다.") Long revision,
        @NotNull(message = "감정을 선택해주세요.") List<DreamEmotion> emotions) { }
