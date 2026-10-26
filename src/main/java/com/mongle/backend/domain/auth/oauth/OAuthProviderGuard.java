package com.mongle.backend.domain.auth.oauth;

import com.mongle.backend.domain.auth.exception.AuthErrorCode;
import com.mongle.backend.global.security.SecurityResponseWriter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Reject disabled or unknown providers before Spring creates an authorization redirect. */
public final class OAuthProviderGuard extends OncePerRequestFilter {
    private final ClientRegistrationRepository registrations;
    private final SecurityResponseWriter responses;

    public OAuthProviderGuard(
            ClientRegistrationRepository registrations, SecurityResponseWriter responses) {
        this.registrations = registrations;
        this.responses = responses;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        for (String prefix : new String[] {"/oauth2/authorization/", "/login/oauth2/code/"}) {
            if (path.startsWith(prefix)
                    && registrations.findByRegistrationId(path.substring(prefix.length()))
                            == null) {
                responses.failure(response, AuthErrorCode.UNSUPPORTED_PROVIDER);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
