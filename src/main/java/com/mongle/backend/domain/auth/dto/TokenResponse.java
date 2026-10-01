package com.mongle.backend.domain.auth.dto;

import com.mongle.backend.domain.user.dto.UserResponse;

public record TokenResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {
}
