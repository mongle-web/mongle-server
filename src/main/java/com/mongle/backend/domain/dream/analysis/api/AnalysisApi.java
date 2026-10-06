package com.mongle.backend.domain.dream.analysis.api;

import com.mongle.backend.domain.dream.analysis.AnalysisResponse;
import com.mongle.backend.domain.dream.dto.DreamRevisionRequest;
import com.mongle.backend.global.config.SwaggerConfig;
import com.mongle.backend.global.response.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

@Tag(name = "DreamAnalysis", description = "꿈 장면 구조화 및 분석 조회")
@SecurityRequirement(name = SwaggerConfig.BEARER_AUTH)
public interface AnalysisApi {
    @Operation(
            summary = "완성 꿈 장면 구조화",
            description =
                    "장면·요소와 20자 이내 자동 제목, 표시 키워드 1~5개를 함께 생성합니다. 사용자가 정하거나 직접 비운 제목은 유지합니다. 자동 제목"
                        + " 저장 후 revision은 dreamRevision을 사용하거나 꿈을 다시 조회하세요. sourceRevision은 유지됩니다."
                        + " 중복 요청은 기존 분석을 반환하며 scene-v1의 제목·키워드는 자동 보충하지 않습니다. 실패 분석은 최신 revision으로"
                        + " 재시도하며 Gateway 미연결은 503입니다.")
    ResponseEntity<ApiResponse<AnalysisResponse>> analyze(
            @Parameter(hidden = true) Jwt jwt,
            @Positive Long dreamId,
            @Valid DreamRevisionRequest request);

    @Operation(
            summary = "내 분석 조회",
            description =
                    "원문 삭제 후에도 소유자만 조회할 수 있습니다. 타인의 분석과 없는 분석은 모두 404입니다. sourceRevision은 AI 입력"
                        + " 버전이며 제목만 수정하면 sourceChanged=false, 원문이 변경되면 true입니다."
                        + " generatedTitle·displayKeywords는 생성 당시 결과이며 원문 삭제 후에도 보존합니다.")
    ResponseEntity<ApiResponse<AnalysisResponse>> get(
            @Parameter(hidden = true) Jwt jwt, @Positive Long analysisId);
}
