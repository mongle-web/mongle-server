package com.mongle.backend.domain.auth;

import static org.mockito.Mockito.*;

import com.mongle.backend.domain.auth.exception.AuthErrorCode;
import com.mongle.backend.domain.auth.oauth.OAuthProviderGuard;
import com.mongle.backend.global.security.SecurityResponseWriter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

class OAuthProviderGuardTest {
    @Test
    void rejectsUnsupportedStartAndCallbackEvenWhenALegacyRegistrationExists() throws Exception {
        var registrations = mock(ClientRegistrationRepository.class);
        when(registrations.findByRegistrationId(anyString())).thenReturn(mock(ClientRegistration.class));
        for (String path : new String[] {"/oauth2/authorization/google", "/login/oauth2/code/google",
                "/oauth2/authorization/unknown", "/login/oauth2/code/unknown",
                "/oauth2/authorization/Kakao", "/oauth2/authorization/kakao/extra"}) {
            var writer = mock(SecurityResponseWriter.class);
            var chain = mock(FilterChain.class);
            var request = new MockHttpServletRequest("GET", path);
            request.setServletPath(path);
            var response = new MockHttpServletResponse();
            new OAuthProviderGuard(registrations, writer).doFilter(request, response, chain);
            verify(writer).failure(response, AuthErrorCode.UNSUPPORTED_PROVIDER);
            verifyNoInteractions(chain);
        }
    }

    @Test
    void allowsRegisteredKakaoAndUnrelatedCookieAuthEndpoints() throws Exception {
        var registrations = mock(ClientRegistrationRepository.class);
        when(registrations.findByRegistrationId("kakao")).thenReturn(mock(ClientRegistration.class));
        var writer = mock(SecurityResponseWriter.class);
        for (String path : new String[] {"/oauth2/authorization/kakao", "/login/oauth2/code/kakao",
                "/api/v1/auth/csrf", "/api/v1/auth/refresh", "/api/v1/auth/logout"}) {
            var request = new MockHttpServletRequest("GET", path);
            request.setServletPath(path);
            var response = new MockHttpServletResponse();
            var chain = mock(FilterChain.class);
            new OAuthProviderGuard(registrations, writer).doFilter(request, response, chain);
            verify(chain).doFilter(request, response);
        }
        verifyNoInteractions(writer);
    }

    @Test
    void rejectsKakaoWhenRegistrationIsUnavailable() throws Exception {
        var registrations = mock(ClientRegistrationRepository.class);
        var writer = mock(SecurityResponseWriter.class);
        var request = new MockHttpServletRequest("GET", "/oauth2/authorization/kakao");
        request.setServletPath("/oauth2/authorization/kakao");
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        new OAuthProviderGuard(registrations, writer).doFilter(request, response, chain);
        verify(writer).failure(response, AuthErrorCode.UNSUPPORTED_PROVIDER);
        verifyNoInteractions(chain);
    }
}
