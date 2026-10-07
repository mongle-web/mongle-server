package com.mongle.backend.domain.user.dto;

import com.mongle.backend.domain.user.entity.User;

public record UserResponse(Long userId, String email, String nickname, boolean onboardingCompleted) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getNickname(),
                user.isOnboardingCompleted());
    }
}
