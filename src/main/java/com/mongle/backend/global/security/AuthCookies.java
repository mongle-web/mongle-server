package com.mongle.backend.global.security;

import com.mongle.backend.domain.auth.config.AuthProperties;
import com.mongle.backend.domain.auth.service.TokenService.IssuedTokens;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

@Component
@RequiredArgsConstructor
public class AuthCookies {
    public static final String REFRESH = "MONGLE_REFRESH";
    public static final String PATH = "/api/v1/auth";
    private final AuthProperties properties;
    private final Clock authClock;

    public void set(HttpServletResponse response, IssuedTokens tokens) {
        Duration remaining = Duration.between(authClock.instant(), tokens.refreshExpiresAt());
        write(response, tokens.refreshToken(), remaining.isNegative() ? Duration.ZERO : remaining);
    }

    public void clear(HttpServletResponse response) {
        write(response, "", Duration.ZERO);
    }

    private void write(HttpServletResponse response, String value, Duration maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(REFRESH, value)
                .httpOnly(true).secure(properties.cookieSecure())
                .sameSite(properties.cookieSameSite()).path(PATH).maxAge(maxAge).build().toString());
    }
}
