package com.mongle.backend.domain.dream.interpretation.api;

import com.mongle.backend.domain.dream.interpretation.InterpretationResponse;
import com.mongle.backend.global.config.SwaggerConfig;
import com.mongle.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

@Tag(name = "RecentInterpretation", description = "최근 꿈 분석 기반 무료 종합 해석")
@SecurityRequirement(name = SwaggerConfig.BEARER_AUTH)
public interface InterpretationApi {
    @Operation(summary = "종합 해석 및 갱신 필요 여부 조회", description = "AI 호출 없이 최신 완성본과 상태를 조회합니다. 대상 없음은 EMPTY, 최초 생성 전은 NOT_GENERATED입니다. 삭제된 꿈을 사용한 본문은 노출하지 않습니다.")
    ResponseEntity<ApiResponse<InterpretationResponse>> get(@Parameter(hidden = true) Jwt jwt);
}
