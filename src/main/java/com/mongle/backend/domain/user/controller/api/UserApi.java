package com.mongle.backend.domain.user.controller.api;

import com.mongle.backend.domain.user.dto.OnboardingRequest;
import com.mongle.backend.domain.user.dto.NicknameUpdateRequest;
import com.mongle.backend.domain.user.dto.UserResponse;
import com.mongle.backend.global.config.SwaggerConfig;
import com.mongle.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

@Tag(name = "User", description = "내 정보·닉네임 온보딩 및 변경")
@SecurityRequirement(name = SwaggerConfig.BEARER_AUTH)
public interface UserApi {
    @Operation(summary = "내 정보 조회", description = "온보딩 전에도 조회할 수 있습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "사용자 정보 및 온보딩 완료 여부")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증 또는 유효하지 않은 토큰")
    ResponseEntity<ApiResponse<UserResponse>> me(@Parameter(hidden = true) Jwt jwt);

    @Operation(summary = "닉네임 온보딩 완료", description = "한글·영문·숫자 2~10자입니다. 최초 완료 시 가입 혜택 기본 3크레딧을 같은 트랜잭션으로 지급합니다. 같은 닉네임의 재요청은 추가 지급 없이 성공하며, 기존 완료 계정에 소급 지급하거나 다른 닉네임으로 덮어쓰지 않습니다. 크레딧 기능 미준비·지급 실패 시 가입 완료도 롤백합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "온보딩 완료")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "닉네임 검증 실패")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증 또는 유효하지 않은 토큰")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "다른 닉네임으로 이미 완료됨")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "CREDIT_503_1 크레딧 기능 비활성 또는 가입 지급량 미준비. 온보딩 변경 없음")
    ResponseEntity<ApiResponse<UserResponse>> onboarding(@Parameter(hidden = true) Jwt jwt, OnboardingRequest request);

    @Operation(summary = "내 닉네임 변경", description = "온보딩을 완료한 본인의 닉네임을 변경합니다. 한글·영문·숫자 2~10자이며 앞뒤 공백도 허용하지 않습니다. "
            + "닉네임 중복은 허용합니다. 같은 닉네임은 수정 시각을 바꾸지 않는 무변경 응답입니다. 닉네임만 입력하며 다른 필드는 거절합니다. "
            + "온보딩 상태와 크레딧은 유지하고 가입 혜택을 다시 지급하지 않습니다. 크레딧 비활성 상태에서도 변경 가능하며 기존 로그인 세션을 유지합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "변경 후 사용자 정보. 동일 닉네임도 200")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "COMMON_400_2 닉네임 검증 실패 / COMMON_400_1 잘못된 본문·허용하지 않은 필드")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "미인증 또는 유효하지 않은 토큰")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "USER_403_1 온보딩 미완료")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "USER_404_1 사용자 없음")
    ResponseEntity<ApiResponse<UserResponse>> updateNickname(@Parameter(hidden = true) Jwt jwt, NicknameUpdateRequest request);
}
