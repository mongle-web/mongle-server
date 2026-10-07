package com.mongle.backend.domain.auth.controller.api;

import com.mongle.backend.domain.auth.dto.CsrfResponse;
import com.mongle.backend.domain.auth.dto.TokenResponse;
import com.mongle.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;

@Tag(name = "Auth", description = "토큰 재발급 및 로그아웃")
public interface AuthApi {
    @Operation(summary = "CSRF 토큰 조회", description = "쿠키를 유지하고 반환된 headerName과 token을 재발급·로그아웃 요청 헤더에 넣습니다.")
    ResponseEntity<ApiResponse<CsrfResponse>> csrf(@Parameter(hidden = true) CsrfToken token);

    @Operation(summary = "인증 토큰 재발급", description = "HttpOnly Refresh 쿠키와 CSRF 헤더가 필요합니다. 성공 시 Refresh 쿠키가 교체되며 이전 값은 사용할 수 없습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "새 Access Token 및 사용자 정보")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Refresh 쿠키가 없거나 만료·변조됨")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 검증 실패")
    ResponseEntity<ApiResponse<TokenResponse>> refresh(@Parameter(hidden = true) String refresh,
                                                       @Parameter(hidden = true) HttpServletResponse response);

    @Operation(summary = "현재 로그인 세션 로그아웃", description = "현재 Refresh 쿠키를 삭제하고 연결된 Access Token을 무효화합니다. 다른 기기의 로그인은 유지합니다. CSRF 헤더가 필요합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그아웃 완료, 이미 로그아웃한 경우 포함")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "CSRF 검증 실패")
    ResponseEntity<ApiResponse<Void>> logout(@Parameter(hidden = true) String refresh,
                                              @Parameter(hidden = true) HttpServletResponse response);
}
