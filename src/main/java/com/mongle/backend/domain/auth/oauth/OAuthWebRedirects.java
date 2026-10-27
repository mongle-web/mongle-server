package com.mongle.backend.domain.auth.oauth;

import com.mongle.backend.domain.auth.config.AuthProperties;
import com.mongle.backend.domain.auth.config.OAuthWebProperties;
import com.mongle.backend.domain.auth.exception.AuthErrorCode;
import com.mongle.backend.global.error.ErrorCode;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Set;

/** Only configured frontend URLs can be used; no request parameter/header is a redirect target. */
@Component
public class OAuthWebRedirects {
    private static final Set<AuthErrorCode> LOGIN_ERRORS =
            Set.of(
                    AuthErrorCode.UNSUPPORTED_PROVIDER, AuthErrorCode.INVALID_PROVIDER_RESPONSE,
                    AuthErrorCode.EMAIL_REQUIRED, AuthErrorCode.EMAIL_NOT_VERIFIED,
                    AuthErrorCode.LOGIN_FAILED, AuthErrorCode.LOGIN_UNAVAILABLE);
    private final URI success;
    private final URI failure;

    public OAuthWebRedirects(
            OAuthWebProperties properties, AuthProperties auth, Environment environment) {
        boolean supplied = hasText(properties.successUrl()) || hasText(properties.failureUrl());
        if (!supplied && !environment.acceptsProfiles(Profiles.of("oauth"))) {
            success = null;
            failure = null;
            return;
        }
        boolean local =
                environment.acceptsProfiles(Profiles.of("local", "test"))
                        && !environment.acceptsProfiles(Profiles.of("mysql", "prod"));
        success = target(properties.successUrl(), auth, local);
        failure = target(properties.failureUrl(), auth, local);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static URI target(String value, AuthProperties auth, boolean local) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || uri.getPath() == null
                    || uri.getPath().contains("\\")
                    || uri.getPath().chars().anyMatch(Character::isISOControl)
                    || !("https".equals(uri.getScheme())
                            || (local && "http".equals(uri.getScheme())))) {
                throw new IllegalArgumentException();
            }
            URI origin =
                    new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null);
            if (auth.allowedOrigins().stream().map(URI::create).noneMatch(origin::equals)) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "OAuth 완료·실패 URL은 허용된 프론트 Origin의 HTTPS 주소여야 하며 query/fragment를 포함할 수 없습니다.");
        }
    }

    public void requireConfigured() {
        if (success == null || failure == null) {
            throw new IllegalStateException("OAuth 완료·실패 URL을 설정해야 합니다.");
        }
    }

    public void success(HttpServletResponse response) {
        requireConfigured();
        redirect(response, success.toASCIIString());
    }

    public void failure(HttpServletResponse response, ErrorCode error) {
        requireConfigured();
        AuthErrorCode code =
                error instanceof AuthErrorCode auth && LOGIN_ERRORS.contains(auth)
                        ? auth
                        : AuthErrorCode.LOGIN_UNAVAILABLE;
        redirect(response, failure.toASCIIString() + "?code=" + code.getCode());
    }

    private void redirect(HttpServletResponse response, String target) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setStatus(HttpServletResponse.SC_SEE_OTHER);
        response.setHeader("Location", target);
    }
}
