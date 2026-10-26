package com.mongle.backend.domain.auth.oauth;

import com.mongle.backend.domain.auth.exception.AuthErrorCode;
import com.mongle.backend.domain.auth.service.TokenService;
import com.mongle.backend.global.error.BusinessException;
import com.mongle.backend.global.security.AuthCookies;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
@Slf4j
@RequiredArgsConstructor
public class OAuthLoginHandlers {
    private final TokenService tokens;
    private final AuthCookies cookies;
    private final OAuthWebRedirects redirects;

    public AuthenticationSuccessHandler success() {
        return (request, response, authentication) -> {
            try {
                redirects.requireConfigured();
                if (!(authentication.getPrincipal() instanceof MongleAuthenticatedUser user)) {
                    redirects.failure(response, AuthErrorCode.LOGIN_FAILED);
                    return;
                }
                var issued = tokens.login(user.getUserResponse().userId());
                cookies.set(response, issued);
                redirects.success(response);
                log.info("소셜 로그인 응답 완료: userId={}", issued.response().user().userId());
            } catch (BusinessException exception) {
                log.warn("소셜 로그인 실패: code={}", exception.getErrorCode().getCode());
                cookies.clear(response);
                redirects.failure(response, exception.getErrorCode());
            } catch (RuntimeException exception) {
                // Provider exception messages can contain tokens or personal information.
                log.error(
                        "소셜 로그인 처리 오류: code={}, errorType={}",
                        AuthErrorCode.LOGIN_UNAVAILABLE.getCode(),
                        exception.getClass().getSimpleName());
                cookies.clear(response);
                redirects.failure(response, AuthErrorCode.LOGIN_UNAVAILABLE);
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
                    code =
                            Arrays.stream(AuthErrorCode.values())
                                    .filter(
                                            value ->
                                                    value.getCode()
                                                            .equals(
                                                                    oauth.getError()
                                                                            .getErrorCode()))
                                    .findFirst()
                                    .orElse(AuthErrorCode.LOGIN_FAILED);
                }
                log.warn(
                        "소셜 인증 실패: code={}, errorType={}",
                        code.getCode(),
                        exception.getClass().getSimpleName());
                redirects.failure(response, code);
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
