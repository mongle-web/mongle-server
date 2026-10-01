package com.mongle.backend.domain.auth.oauth;

import com.mongle.backend.domain.user.dto.UserResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import java.util.List;
import java.util.Map;

public final class MongleOAuth2User extends DefaultOAuth2User implements MongleAuthenticatedUser {
    private final UserResponse user;

    public MongleOAuth2User(UserResponse user) {
        super(List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("userId", user.userId().toString()), "userId");
        this.user = user;
    }

    @Override
    public UserResponse getUserResponse() {
        return user;
    }
}
