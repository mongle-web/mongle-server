package com.mongle.backend.domain.user.controller.api;

import com.mongle.backend.domain.user.dto.OnboardingRequest;
import com.mongle.backend.domain.user.dto.UserResponse;
import com.mongle.backend.global.config.SwaggerConfig;
import com.mongle.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

@Tag(name = "User", description = "내 정보 및 닉네임 온보딩")
@SecurityRequirement(name = SwaggerConfig.BEARER_AUTH)
public interface UserApi {
    @Operation(summary = "내 정보 조회", description = "온보딩 전에도 조회할 수 있습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "사용자 정보 및 온보딩 완료 여부")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증 또는 유효하지 않은 토큰")
    ResponseEntity<ApiResponse<UserResponse>> me(@Parameter(hidden = true) Jwt jwt);

    @Operation(summary = "닉네임 온보딩 완료", description = "한글·영문·숫자 2~10자입니다. 같은 닉네임의 재요청은 성공하며, 완료 후 다른 닉네임으로 덮어쓸 수 없습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "온보딩 완료")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "닉네임 검증 실패")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증 또는 유효하지 않은 토큰")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "다른 닉네임으로 이미 완료됨")
    ResponseEntity<ApiResponse<UserResponse>> onboarding(@Parameter(hidden = true) Jwt jwt, OnboardingRequest request);
}
