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
    @Operation(
            summary = "종합 해석 명시적 갱신",
            description =
                    "변경 안내 후 갱신 버튼에서 호출합니다. 현재 대상의 성공본이 이미 있으면 재사용하고, 진행 중 시도는 중복 생성하지 않습니다. 실패해도"
                            + " 유효한 이전 본문을 유지하며 재시도할 수 있습니다. 크레딧 차감 없이 동작합니다.")
    java.util.concurrent.CompletableFuture<ResponseEntity<ApiResponse<InterpretationResponse>>>
            refresh(@Parameter(hidden = true) Jwt jwt);

    @Operation(
            summary = "첫 진입 종합 해석 생성",
            description =
                    "화면 첫 진입 시 호출합니다. 최초 성공 전만 생성/재시도하고 동일 대상은 재사용합니다. 이전 성공 후 대상 변경은"
                        + " refreshNeeded로 안내하며 자동 갱신하지 않습니다. 진행 중은 202, 완료·실패·빈 상태는 200입니다. 무료이며"
                        + " 생성 비활성/키 미설정은 503입니다.")
    java.util.concurrent.CompletableFuture<ResponseEntity<ApiResponse<InterpretationResponse>>>
            ensure(@Parameter(hidden = true) Jwt jwt);

    @Operation(
            summary = "종합 해석 및 갱신 필요 여부 조회",
            description =
                    "AI 호출 없이 최신 완성본과 상태를 조회합니다. 대상 없음은 EMPTY, 최초 생성 전은 NOT_GENERATED입니다. 삭제된 꿈을"
                            + " 사용한 본문은 노출하지 않습니다.")
    ResponseEntity<ApiResponse<InterpretationResponse>> get(@Parameter(hidden = true) Jwt jwt);
}
