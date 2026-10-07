package com.mongle.backend.domain.auth.controller;

import com.mongle.backend.domain.auth.controller.api.AuthApi;
import com.mongle.backend.domain.auth.dto.CsrfResponse;
import com.mongle.backend.domain.auth.dto.TokenResponse;
import com.mongle.backend.domain.auth.service.TokenService;
import com.mongle.backend.global.response.ApiResponse;
import com.mongle.backend.global.security.AuthCookies;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
public class AuthController implements AuthApi {
    private final TokenService tokens;
    private final AuthCookies cookies;

    @Override
    @GetMapping("/csrf")
    public ResponseEntity<ApiResponse<CsrfResponse>> csrf(CsrfToken token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(new CsrfResponse(token.getHeaderName(), token.getToken())));
    }

    @Override
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(
            @CookieValue(name = AuthCookies.REFRESH, required = false) String refresh,
            HttpServletResponse response) {
        // 동시 요청의 늦은 실패 응답이 먼저 발급된 새 쿠키를 지우지 않도록 한다.
        var issued = tokens.refresh(refresh);
        cookies.set(response, issued);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(issued.response()));
    }

    @Override
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @CookieValue(name = AuthCookies.REFRESH, required = false) String refresh,
            HttpServletResponse response) {
        tokens.logout(refresh);
        cookies.clear(response);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.emptySuccess());
    }
}
