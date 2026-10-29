package com.mongle.backend.domain.world.summary;

import com.mongle.backend.global.config.SwaggerConfig;
import com.mongle.backend.global.response.ApiResponse;

import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.constraints.Positive;

import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

@Tag(name = "World Summary", description = "완성 세계관의 AI 제목·근거 기반 공통 키워드·반복 감정. 크레딧을 사용하지 않습니다.")
@SecurityRequirement(name = SwaggerConfig.BEARER_AUTH)
public interface WorldSummaryApi {
    @Operation(
            summary = "세계관 제목·키워드·감정 및 생성 상태 조회",
            description =
                    "GET은 AI를 호출하지 않습니다. 폴더 name과 generatedTitle은 별개입니다. 세계관 완성·부분 반영 성공 후 자동"
                        + " 접수됩니다. commonKeywords와 recurringEmotions는 고정된 서사 버전의 분석·감정에서 서로 다른 꿈 2개"
                        + " 이상에 등장한 항목이며 dreamCount는 해당 꿈 수입니다. 근거가 없으면 빈 배열입니다. sourceCount는 표시"
                        + " 결과의 출처 수이며, limitedEvidence는 꿈이 2개 미만이거나 일부 구버전 분석·감정 스냅샷이 없는 경우입니다."
                        + " refreshNeeded는 원문 변경·완성본 교체·설정 변경·미생성 안내입니다. 원문 변경은 먼저 새 서사를 세계관에 반영해야"
                        + " 합니다. 생성 중·실패 시 hasPreviousResult=true이면 이전 성공 결과를 표시할 수 있습니다. 삭제된 꿈을"
                        + " 사용한 텍스트 결과는 제거합니다. compositionRevision은 현재 완성본,"
                        + " resultCompositionRevision은 표시 결과의 버전입니다. 본인 소유·온보딩 완료 회원만 접근 가능합니다.")
    ResponseEntity<ApiResponse<WorldSummaryResponse>> get(
            @Parameter(hidden = true) Jwt jwt, @Positive Long worldId);

    @Operation(
            summary = "세계관 제목·키워드·감정 생성 또는 재시도 접수",
            description =
                    "요청 본문이 없습니다. 현재 완성본으로 무료 생성하며 같은 입력의 성공 결과·진행 작업은 재사용합니다."
                        + " 202=PENDING/PROCESSING, 200=완료 재사용 또는 EMPTY. 기존 완성본도 이 API로 생성합니다. 접수 후"
                        + " GET으로 상태를 확인합니다. 실패는 자동 재호출하지 않으며 같은 POST로 명시적으로 재시도합니다. 세계관 구성 생성 중은"
                        + " 409, 완성본 없음은 409, 입력 크기 초과는 400, 생성 비활성·미설정은 503입니다. 출처가 0개면 EMPTY이며"
                        + " AI를 호출하지 않습니다. 1개인 기존 완성본도 제목을 만들 수 있으나 반복 근거는 빈 배열입니다. 세계관 이미지의 최소 꿈"
                        + " 2개 정책과는 별개입니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "202",
            description = "작업 접수 또는 진행 중",
            useReturnTypeSchema = true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "완료 결과 재사용 또는 출처 없음",
            useReturnTypeSchema = true)
    ResponseEntity<ApiResponse<WorldSummaryResponse>> request(
            @Parameter(hidden = true) Jwt jwt, @Positive Long worldId);
}
