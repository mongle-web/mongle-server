package com.mongle.backend.domain.dream.analysis.api;

import com.mongle.backend.domain.dream.analysis.AnalysisRecordService;
import com.mongle.backend.domain.dream.analysis.RecurringElementsResponse;
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
            description = "동일 키워드는 꿈별 1건으로 집계합니다. 삭제된 꿈과 과거 꿈 FK가 해제된 분석은 분석 건수·키워드·선택 감정에서 제외합니다.")
    ResponseEntity<ApiResponse<AnalysisRecordService.Monthly>> monthly(
            @Parameter(hidden = true) Jwt jwt, String month);

    @Operation(
            summary = "월별 반복 장소·인물 조회",
            description =
                    "month(YYYY-MM)을 생략하면 한국 시간의 현재 월입니다. 꿈 날짜 기준 본인의 완료 기록과 마지막 성공 분석을 집계합니다. "
                            + "서로 다른 꿈 2개 이상에 등장한 PLACE와 CHARACTER를 각각 최대 5개 제공합니다. "
                            + "같은 꿈의 여러 장면은 1건이며 기존 정규화 이름을 유형별로 집계합니다. 정규화 키가 없는 과거 요소는 제외합니다. "
                            + "dreamCount 내림차순·정규화 이름 오름차순이며 name은 해당 집계에 존재하는 표시 이름입니다. "
                            + "재생성 진행·실패 시 유효한 이전 성공 결과를 유지하고 새 분석·서사 성공 확정 후 반영합니다. "
                            + "원문·감정 수정만으로 자동 분석하지 않으며 삭제·초안·실패만 있는 분석은 제외합니다. "
                            + "analyzedDreamCount는 반복 요소가 없는 분석도 포함합니다. 빈 달이나 반복 근거가 없으면 빈 배열입니다. "
                            + "AI 호출·크레딧 차감 없이 조회하며, 다른 이름이 동일 인물인지 추론하지 않습니다.")
    ResponseEntity<ApiResponse<RecurringElementsResponse>> recurringElements(
            @Parameter(hidden = true) Jwt jwt,
            @Parameter(description = "조회할 월. 생략 시 한국 시간 현재 월", example = "2026-10") String month);

    @Operation(
            summary = "최근 꿈 해석 대상 조회",
            description =
                    "현재 꿈이 존재하는 성공 분석만 꿈 날짜 기준 최근 30일·최대 10건입니다. 3건 미만이면 limitedEvidence=true입니다. 이"
                        + " API는 분석 목록만 반환하며 종합 해석 생성·조회·갱신은 /analysis-records/interpretation API를"
                        + " 사용합니다.")
    ResponseEntity<ApiResponse<AnalysisRecordService.Recent>> recent(
            @Parameter(hidden = true) Jwt jwt);
}
