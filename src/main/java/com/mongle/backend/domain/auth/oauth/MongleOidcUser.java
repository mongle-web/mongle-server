package com.mongle.backend.domain.auth.oauth;

import com.mongle.backend.domain.user.dto.UserResponse;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

public final class MongleOidcUser extends DefaultOidcUser implements MongleAuthenticatedUser {
    private final UserResponse user;

    public MongleOidcUser(OidcUser delegate, UserResponse user) {
        super(delegate.getAuthorities(), delegate.getIdToken(), delegate.getUserInfo(), "sub");
        this.user = user;
    }

    @Override
    public UserResponse getUserResponse() {
        return user;
    }
}
