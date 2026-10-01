package com.mongle.backend.domain.auth;

import com.mongle.backend.domain.auth.oauth.MongleOAuth2User;
import com.mongle.backend.domain.auth.oauth.OAuthLoginHandlers;
import com.mongle.backend.domain.user.dto.UserResponse;
import com.mongle.backend.global.security.SecurityResponseWriter;
import com.mongle.backend.global.security.AuthCookies;
import com.mongle.backend.domain.auth.service.TokenService;
import com.mongle.backend.domain.auth.dto.TokenResponse;
import java.time.Instant;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthLoginHandlersTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final TokenService tokens = mock(TokenService.class);
    private final AuthCookies cookies = mock(AuthCookies.class);
    private final OAuthLoginHandlers handlers = new OAuthLoginHandlers(new SecurityResponseWriter(json), tokens, cookies);

    @Test
    void issuesMongleTokensWithoutExposingProviderTokensAndClearsOAuthSession() throws Exception {
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute("oauth-secret", "provider-token");
        var response = new MockHttpServletResponse();
        var principal = new MongleOAuth2User(new UserResponse(7L, "user@example.com", null, false));
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        var issued = new TokenService.IssuedTokens(new TokenResponse("mongle-access", "Bearer", 900,
                principal.getUserResponse()), "opaque-refresh", Instant.now().plusSeconds(3600));
        when(tokens.login(7L)).thenReturn(issued);

        handlers.success().onAuthenticationSuccess(request, response, authentication);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(json.readTree(response.getContentAsString()).at("/data/user/userId").asLong()).isEqualTo(7L);
        assertThat(json.readTree(response.getContentAsString()).at("/data/accessToken").asString()).isEqualTo("mongle-access");
        assertThat(response.getContentAsString()).doesNotContain("provider-token", "opaque-refresh", "refreshToken");
        verify(cookies).set(response, issued);
        assertThat(request.getSession(false)).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void hidesProviderDescriptionsAndClearsFailedLoginSession() throws Exception {
        var request = new MockHttpServletRequest();
        request.getSession();
        var response = new MockHttpServletResponse();
        var exception = new OAuth2AuthenticationException(new OAuth2Error("access_denied"),
                "외부 제공사의 민감한 오류 설명");

        handlers.failure().onAuthenticationFailure(request, response, exception);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(json.readTree(response.getContentAsString()).get("code").asString()).isEqualTo("AUTH_LOGIN_FAILED");
        assertThat(response.getContentAsString()).doesNotContain("민감한");
        assertThat(request.getSession(false)).isNull();
    }
}
