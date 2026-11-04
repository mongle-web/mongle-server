package com.mongle.backend.domain.credit;

import com.mongle.backend.global.config.SwaggerConfig;
import com.mongle.backend.global.response.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

@Tag(name = "Attendance", description = "한국 시간 기준 하루 한 번 출석 보상 수령")
@SecurityRequirement(name = SwaggerConfig.BEARER_AUTH)
@ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(schema = @Schema(implementation = ApiResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "온보딩 필요", content = @Content(schema = @Schema(implementation = ApiResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "CREDIT_404_1 사용자 없음", content = @Content(schema = @Schema(implementation = ApiResponse.class)))
})
public interface AttendanceApi {
    @Operation(summary = "오늘 출석 보상 상태", description = "조회만 하며 계정 생성·크레딧 지급은 없습니다. 기능 비활성 상태에서도 조회할 수 있습니다. rewardAmount는 수령 전에는 현재 설정량, 수령 후에는 실제 지급량입니다. nextClaimAt은 다음 날짜 보상이 열리는 시각입니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공", useReturnTypeSchema = true)
    ResponseEntity<ApiResponse<AttendanceStatus>> status(@Parameter(hidden = true) Jwt jwt);

    @Operation(summary = "오늘 출석 보상 수령", description = "요청 본문 없이 호출합니다. 사용자·날짜·지급량은 서버에서 결정합니다. 한국 시간 자정에 새 보상이 열리며 소급 지급하지 않습니다. 반복 요청도 200이며 alreadyClaimed=true와 기존 지급량·현재 잔액을 반환합니다. 이미 수령한 당일 보상의 재확인은 기능 비활성 상태에서도 가능합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수령 또는 기존 수령 재확인", useReturnTypeSchema = true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "CREDIT_409_2 지급 키 충돌 / CREDIT_409_4 잔액 범위 초과", content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "CREDIT_503_1 전체 크레딧 또는 출석 보상 비활성", content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    ResponseEntity<ApiResponse<AttendanceClaim>> claim(@Parameter(hidden = true) Jwt jwt);
}
