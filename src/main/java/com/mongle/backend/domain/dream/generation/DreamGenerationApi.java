package com.mongle.backend.domain.dream.generation;

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

@Tag(name = "Dream Generation", description = "저장 완료 후 자동 분석·서사화 상태 및 재시도")
@SecurityRequirement(name = SwaggerConfig.BEARER_AUTH)
public interface DreamGenerationApi {
    @Operation(summary = "최신 입력으로 분석·서사 명시적 재생성", description = """
            꿈 revision과 generation 조회의 generationVersion을 전달합니다.
            진행 중 동일 입력 작업은 재사용하며 완료된 요청의 재전송은 409입니다.
            새 분석·서사가 모두 성공할 때만 기존 결과를 교체합니다. 이미지·세계관은 갱신하지 않습니다.
            대기·진행 중 꿈 수정·삭제는 409입니다. 크레딧 차감은 아직 연동하지 않습니다.
            """)
    ResponseEntity<ApiResponse<DreamGenerationResponse>> regenerate(
            @Parameter(hidden = true) Jwt jwt, @Positive Long dreamId,
            @Valid DreamRegenerationRequest request);

    @Operation(
            summary = "자동 생성 진행 상태 조회",
            description = """
                    stage는 ANALYSIS/STORY, status는 QUEUED/PROCESSING/COMPLETED/FAILED입니다.
                    sourceChanged는 원문 변경 여부이며 결과 ID로 기존 조회 API를 사용합니다.
                    원문과 감정은 반환하지 않습니다. 기존 기록에 자동 작업이 없으면 404입니다.
                    """)
    ResponseEntity<ApiResponse<DreamGenerationResponse>> get(
            @Parameter(hidden = true) Jwt jwt, @Positive Long dreamId);

    @Operation(
            summary = "실패한 자동 생성 단계 재시도",
            description = """
                    최신 꿈 revision을 전달합니다. 분석 성공 후 서사화가 실패하면 서사화만 재시도합니다.
                    202는 현재 상태의 반환이며, FAILED 작업만 재예약합니다.
                    대기·진행·완료 작업은 재예약하지 않습니다. 원문이 바뀐 작업은 409입니다.
                    재시도 시 외부 호출 비용이 발생할 수 있습니다.
                    """)
    ResponseEntity<ApiResponse<DreamGenerationResponse>> retry(
            @Parameter(hidden = true) Jwt jwt,
            @Positive Long dreamId,
            @Valid DreamRevisionRequest request);
}
