package com.mongle.backend.domain.auth.oauth;

import com.mongle.backend.domain.user.dto.UserResponse;

public interface MongleAuthenticatedUser {
    UserResponse getUserResponse();
}
