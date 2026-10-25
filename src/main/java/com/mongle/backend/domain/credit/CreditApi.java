package com.mongle.backend.domain.credit;

import com.mongle.backend.global.config.SwaggerConfig;
import com.mongle.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

@Tag(name = "Credit", description = "내 크레딧 조회. 지급·예약·확정·해제는 서버 내부 전용입니다.")
@SecurityRequirement(name = SwaggerConfig.BEARER_AUTH)
@ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(schema = @Schema(implementation = ApiResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "온보딩 필요", content = @Content(schema = @Schema(implementation = ApiResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "CREDIT_404_1 사용자 없음", content = @Content(schema = @Schema(implementation = ApiResponse.class)))
})
public interface CreditApi {
    @Operation(summary = "내 크레딧 잔액", description = "사용 가능·예약 중·총 잔액을 조회합니다. 계정에 지급 내역이 없으면 모두 0이며 조회로 지급이나 계정 생성이 발생하지 않습니다. 크레딧 기능 비활성 상태에서도 조회할 수 있습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공", useReturnTypeSchema = true)
    ResponseEntity<ApiResponse<CreditBalance>> balance(@Parameter(hidden = true) Jwt jwt);
}
