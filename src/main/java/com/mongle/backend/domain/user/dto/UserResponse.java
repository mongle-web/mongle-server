package com.mongle.backend.domain.user.dto;

import com.mongle.backend.domain.user.entity.User;

public record UserResponse(Long userId, String email, String nickname, boolean onboardingCompleted,
        @io.swagger.v3.oas.annotations.media.Schema(description="현재 공개된 이용약관과 개인정보 수집·이용 필수 동의를 모두 완료했는지 여부. 버전과 문서 해시를 서버 설정과 대조합니다.")
        boolean termsAccepted) {

    public static UserResponse from(User user,boolean termsAccepted) {
        return new UserResponse(user.getId(), user.getEmail(), user.getNickname(),
                user.isOnboardingCompleted(), termsAccepted);
    }
}
