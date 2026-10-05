package com.mongle.backend.domain.dream.story;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record StoryRequest(
        @NotNull(message = "현재 꿈 버전을 전달해주세요.") @PositiveOrZero(message = "버전은 0 이상이어야 합니다.")
                Long revision,
        Boolean regenerate,
        @PositiveOrZero(message = "이야기 버전은 0 이상이어야 합니다.") Long storyVersion) {
    public StoryRequest {
        // 생략 또는 null인 선택 항목은 최초 생성 요청으로 정규화한다.
        regenerate = Boolean.TRUE.equals(regenerate);
    }

    @AssertTrue(message = "재생성 시 현재 이야기 버전을 전달해주세요.")
    public boolean isRegenerationVersionProvided() {
        return !regenerate || storyVersion != null;
    }
}
