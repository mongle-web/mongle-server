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
    private final SecurityResponseWriter responses;
    private final TokenService tokens;
    private final AuthCookies cookies;

    public AuthenticationSuccessHandler success() {
        return (request, response, authentication) -> {
            try {
                if (!(authentication.getPrincipal() instanceof MongleAuthenticatedUser user)) {
                    log.warn("소셜 로그인 실패: code={}", AuthErrorCode.LOGIN_FAILED.getCode());
                    responses.failure(response, AuthErrorCode.LOGIN_FAILED);
                    return;
                }
                var issued = tokens.login(user.getUserResponse().userId());
                cookies.set(response, issued);
                responses.write(response, 200, ApiResponse.success(issued.response()));
                log.info("소셜 로그인 응답 완료: userId={}", issued.response().user().userId());
            } catch (BusinessException exception) {
                log.warn("소셜 로그인 실패: code={}", exception.getErrorCode().getCode());
                cookies.clear(response);
                responses.failure(response, exception.getErrorCode());
            } catch (RuntimeException exception) {
                // 제공자 예외 메시지에는 토큰이나 사용자 정보가 섞일 수 있어 종류만 기록한다.
                log.error("소셜 로그인 처리 오류: code={}, errorType={}", AuthErrorCode.LOGIN_UNAVAILABLE.getCode(), exception.getClass().getSimpleName());
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
                log.warn("소셜 인증 실패: code={}, errorType={}", code.getCode(), exception.getClass().getSimpleName());
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
