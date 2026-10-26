package com.mongle.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.mongle.backend.domain.auth.dto.TokenResponse;
import com.mongle.backend.domain.auth.exception.AuthErrorCode;
import com.mongle.backend.domain.auth.oauth.MongleOAuth2User;
import com.mongle.backend.domain.auth.oauth.OAuthLoginHandlers;
import com.mongle.backend.domain.auth.service.TokenService;
import com.mongle.backend.domain.user.dto.UserResponse;
import com.mongle.backend.global.error.BusinessException;
import com.mongle.backend.global.security.AuthCookies;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import java.time.Instant;

class OAuthLoginHandlersTest {
    private final TokenService tokens = mock(TokenService.class);
    private final AuthCookies cookies = mock(AuthCookies.class);
    private final OAuthLoginHandlers handlers =
            new OAuthLoginHandlers(tokens, cookies, OAuthWebRedirectsTest.configured());

    @Test
    void issuesMongleTokensWithoutExposingProviderTokensAndClearsOAuthSession() throws Exception {
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute("oauth-secret", "provider-token");
        var response = new MockHttpServletResponse();
        var principal = new MongleOAuth2User(new UserResponse(7L, "user@example.com", null, false));
        var authentication =
                new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        var issued =
                new TokenService.IssuedTokens(
                        new TokenResponse(
                                "mongle-access", "Bearer", 900, principal.getUserResponse()),
                        "opaque-refresh",
                        Instant.now().plusSeconds(3600));
        when(tokens.login(7L)).thenReturn(issued);

        handlers.success().onAuthenticationSuccess(request, response, authentication);
        assertThat(response.getStatus()).isEqualTo(303);
        assertThat(response.getRedirectedUrl()).isEqualTo("https://mongle.test/auth/callback");
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(response.getContentAsString())
                .doesNotContain("provider-token", "opaque-refresh", "refreshToken");
        verify(cookies).set(response, issued);
        assertThat(request.getSession(false)).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void hidesProviderDescriptionsAndClearsFailedLoginSession() throws Exception {
        var request = new MockHttpServletRequest();
        request.getSession();
        var response = new MockHttpServletResponse();
        var exception =
                new OAuth2AuthenticationException(
                        new OAuth2Error("access_denied"), "외부 제공사의 민감한 오류 설명");

        handlers.failure().onAuthenticationFailure(request, response, exception);
        assertThat(response.getStatus()).isEqualTo(303);
        assertThat(response.getRedirectedUrl())
                .isEqualTo("https://mongle.test/login?code=AUTH_401_6");
        assertThat(response.getContentAsString()).doesNotContain("민감한");
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void sessionIssueFailuresClearRefreshAndReturnOnlyServiceErrors() throws Exception {
        var principal = new MongleOAuth2User(new UserResponse(7L, "user@example.com", null, false));
        var authentication =
                new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities());
        for (var failure :
                new RuntimeException[] {
                    new BusinessException(AuthErrorCode.EMAIL_REQUIRED),
                    new IllegalStateException("private-provider-token")
                }) {
            var request = new MockHttpServletRequest();
            request.getSession();
            var response = new MockHttpServletResponse();
            doThrow(failure).when(tokens).login(7L);
            handlers.success().onAuthenticationSuccess(request, response, authentication);
            assertThat(response.getStatus()).isEqualTo(303);
            assertThat(response.getRedirectedUrl())
                    .isEqualTo(
                            failure instanceof BusinessException
                                    ? "https://mongle.test/login?code=AUTH_401_2"
                                    : "https://mongle.test/login?code=AUTH_503_1");
            assertThat(response.getContentAsString()).isEmpty();
            assertThat(request.getSession(false)).isNull();
        }
        verify(cookies, times(2)).clear(any());
        verify(cookies, never()).set(any(), any());
    }
}
