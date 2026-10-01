package com.mongle.backend.domain.auth.oauth;

import com.mongle.backend.domain.auth.exception.AuthErrorCode;
import com.mongle.backend.global.response.ApiResponse;
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

    public AuthenticationSuccessHandler success() {
        return (request, response, authentication) -> {
            try {
                if (!(authentication.getPrincipal() instanceof MongleAuthenticatedUser user)) {
                    responses.failure(response, AuthErrorCode.LOGIN_FAILED);
                    return;
                }
                // 현재는 소셜 로그인과 계정 저장 결과를 반환한다.
                // JWT·리프레시 토큰 발급과 로그인 완료 화면 이동은 다음 단계에서 연결한다.
                responses.write(response, 200, ApiResponse.success(user.getUserResponse()));
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
