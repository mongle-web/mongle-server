package com.mongle.backend.domain.dream.analysis.api;

import com.mongle.backend.domain.dream.analysis.AnalysisRecordService;
import com.mongle.backend.global.config.SwaggerConfig;
import com.mongle.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

@Tag(name = "AnalysisRecord", description = "꿈 분석 기록 통계")
@SecurityRequirement(name = SwaggerConfig.BEARER_AUTH)
public interface AnalysisRecordApi {
    @Operation(
            summary = "월별 꿈 요소 및 선택 감정 통계",
            description = "동일 키워드는 꿈별 1건으로 집계합니다. 삭제된 꿈의 보존 분석은 키워드에 포함하고 선택 감정에서는 제외합니다.")
    ResponseEntity<ApiResponse<AnalysisRecordService.Monthly>> monthly(
            @Parameter(hidden = true) Jwt jwt, String month);

    @Operation(
            summary = "최근 꿈 해석 대상 조회",
            description =
                    "꿈 날짜 기준 최근 30일·최대 10건입니다. 3건 미만이면 limitedEvidence=true이며 해석 문장 생성은 후속 Gateway 연동에서 구현합니다.")
    ResponseEntity<ApiResponse<AnalysisRecordService.Recent>> recent(
            @Parameter(hidden = true) Jwt jwt);
}
