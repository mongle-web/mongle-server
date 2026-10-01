package com.mongle.backend.domain.auth.oauth;

import com.mongle.backend.domain.auth.exception.AuthErrorCode;
import com.mongle.backend.global.response.ApiResponse;
import com.mongle.backend.domain.auth.service.TokenService;
import com.mongle.backend.global.security.AuthCookies;
import com.mongle.backend.global.error.BusinessException;
import com.mongle.backend.global.security.SecurityResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
@RequiredArgsConstructor
public class OAuthLoginHandlers {
    private final SecurityResponseWriter responses;
    private final TokenService tokens;
    private final AuthCookies cookies;

    public AuthenticationSuccessHandler success() {
        return (request, response, authentication) -> {
            try {
                if (!(authentication.getPrincipal() instanceof MongleAuthenticatedUser user)) {
                    responses.failure(response, AuthErrorCode.LOGIN_FAILED);
                    return;
                }
                var issued = tokens.login(user.getUserResponse().userId());
                cookies.set(response, issued);
                responses.write(response, 200, ApiResponse.success(issued.response()));
            } catch (BusinessException exception) {
                cookies.clear(response);
                responses.failure(response, exception.getErrorCode());
            } catch (RuntimeException exception) {
                cookies.clear(response);
                responses.failure(response, AuthErrorCode.LOGIN_UNAVAILABLE);
            } finally {
                clear(request);
            }
        };
    }

    public AuthenticationFailureHandler failure() {
        return (request, response, exception) -> {
            try {
                AuthErrorCode code = AuthErrorCode.LOGIN_FAILED;
                if (exception instanceof OAuth2AuthenticationException oauth) {
                    code = Arrays.stream(AuthErrorCode.values())
                            .filter(value -> value.getCode().equals(oauth.getError().getErrorCode()))
                            .findFirst().orElse(AuthErrorCode.LOGIN_FAILED);
                }
                responses.failure(response, code);
            } finally {
                clear(request);
            }
        };
    }

    private void clear(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
