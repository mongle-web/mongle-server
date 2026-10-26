package com.mongle.backend.domain.auth;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.auth.config.AuthProperties;
import com.mongle.backend.domain.auth.config.OAuthWebProperties;
import com.mongle.backend.domain.auth.exception.AuthErrorCode;
import com.mongle.backend.domain.auth.oauth.OAuthWebRedirects;
import com.mongle.backend.global.error.CommonErrorCode;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;

class OAuthWebRedirectsTest {
    static AuthProperties auth(String origin) {
        return new AuthProperties(
                null,
                "issuer",
                "audience",
                Duration.ofMinutes(15),
                Duration.ofDays(14),
                true,
                "Lax",
                List.of(origin));
    }

    static OAuthWebRedirects configured() {
        return new OAuthWebRedirects(
                new OAuthWebProperties(
                        "https://mongle.test/auth/callback", "https://mongle.test/login"),
                auth("https://mongle.test"),
                new MockEnvironment());
    }

    @Test
    void rejectsUntrustedOriginsAndAmbiguousOrSensitiveUrlPartsAtStartup() {
        for (String target :
                new String[] {
                    "https://evil.test/login",
                    "https://mongle.test.evil.test/login",
                    "https://mongle.test@evil.test/login",
                    "//mongle.test/login",
                    "http://mongle.test/login",
                    "https://mongle.test/login?token=secret",
                    "https://mongle.test/login#secret",
                    "https://mongle.test/login%0d%0aLocation:evil",
                    "https://mongle.test/\\evil",
                    "https://mongle.test:8443/login",
                    "",
                    "${missing}"
                }) {
            assertThatThrownBy(
                            () ->
                                    new OAuthWebRedirects(
                                            new OAuthWebProperties(
                                                    target, "https://mongle.test/login"),
                                            auth("https://mongle.test"),
                                            new MockEnvironment()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining("secret");
        }
    }

    @Test
    void requiresTargetsForOauthButAllowsNormalApiStartupWithoutOauth() {
        var environment = new MockEnvironment();
        new OAuthWebRedirects(
                new OAuthWebProperties(null, null), auth("https://mongle.test"), environment);
        environment.setActiveProfiles("oauth");
        assertThatThrownBy(
                        () ->
                                new OAuthWebRedirects(
                                        new OAuthWebProperties(null, null),
                                        auth("https://mongle.test"),
                                        environment))
                .isInstanceOf(IllegalStateException.class);
        environment.setActiveProfiles("local", "oauth");
        new OAuthWebRedirects(
                new OAuthWebProperties(
                        "http://localhost:5173/auth/callback", "http://localhost:5173/login"),
                auth("http://localhost:5173"),
                environment);
        environment.setActiveProfiles("local", "mysql", "oauth");
        assertThatThrownBy(
                        () ->
                                new OAuthWebRedirects(
                                        new OAuthWebProperties(
                                                "http://localhost:5173/auth/callback",
                                                "http://localhost:5173/login"),
                                        auth("http://localhost:5173"),
                                        environment))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sendsOnlyAllowlistedErrorCodesWithoutCachingOrReferrerLeaks() {
        var redirect = configured();
        for (var code :
                List.of(
                        AuthErrorCode.EMAIL_REQUIRED,
                        AuthErrorCode.EMAIL_NOT_VERIFIED,
                        AuthErrorCode.LOGIN_FAILED)) {
            var response = new MockHttpServletResponse();
            redirect.failure(response, code);
            assertThat(response.getRedirectedUrl())
                    .isEqualTo("https://mongle.test/login?code=" + code.getCode());
            assertThat(response.getStatus()).isEqualTo(303);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        }
        var response = new MockHttpServletResponse();
        redirect.failure(response, CommonErrorCode.INTERNAL_SERVER_ERROR);
        assertThat(response.getRedirectedUrl())
                .isEqualTo("https://mongle.test/login?code=AUTH_503_1");
    }
}
