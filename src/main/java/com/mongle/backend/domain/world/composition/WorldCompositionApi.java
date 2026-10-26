package com.mongle.backend.domain.world.composition;

import com.mongle.backend.global.config.SwaggerConfig;
import com.mongle.backend.global.response.ApiResponse;

import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

@Tag(name = "World Composition", description = "고정 성공 서사로 세계관을 구성합니다. 연결·부분 반영은 무료입니다.")
@SecurityRequirement(name = SwaggerConfig.BEARER_AUTH)
@ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "입력 형식·페이지 커서 오류"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "인증 필요"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "온보딩 필요"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "없는 폴더·출처 또는 타인 소유"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description =
                    "COMPOSITION_409_1 버전 충돌, COMPOSITION_409_2 최소 꿈 수, COMPOSITION_409_3 현재 서사 필요,"
                        + " COMPOSITION_409_4 작업 중, COMPOSITION_409_5 재시도 상태 오류, WORLD_409_1 폴더 버전"
                        + " 충돌, DREAM_409_4 서사 생성 중"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "503",
            description = "COMPOSITION_503_1 생성 비활성")
})
public interface WorldCompositionApi {
    @Operation(
            summary = "세계관 완성본 생성",
            description =
                    "선택한 꿈이 2개 이상이고 현재 원문·감정으로 완료한 서사가 필요합니다. 성공 버전을 날짜순으로 고정합니다. POST는 작업 접수만 수행하며"
                            + " 상태를 조회해야 합니다. 같은 진행 요청은 재사용하고 실패 시 이전 완성본을 보존합니다. folderRevision과"
                            + " compositionRevision은 필수이며 최초 구성 버전은 0입니다. 크레딧은 차감하지 않습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "202",
            description = "작업 접수",
            useReturnTypeSchema = true)
    ResponseEntity<ApiResponse<WorldCompositionResponse>> build(
            @Parameter(hidden = true) Jwt jwt,
            @Positive Long worldId,
            @Valid WorldBuildRequest request);

    @Operation(
            summary = "세계관 완성본·작업 상태 조회",
            description =
                    "폴더 revision·완성본 compositionRevision·작업 jobVersion을 구분합니다. GET은 AI를 호출하지 않습니다."
                        + " 생성 중이거나 실패해도 hasResult=true이면 기존 완성본을 표시할 수 있습니다. attemptId=null은 아직 생성"
                        + " 요청이 없음을 뜻합니다."
                        + " selectionChanged·sourceChanged·newStoryAvailable·hasConnectionGaps로 현재"
                        + " 완성본의 변경 안내를 표시합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "상태 조회",
            useReturnTypeSchema = true)
    ResponseEntity<ApiResponse<WorldCompositionResponse>> get(
            @Parameter(hidden = true) Jwt jwt, @Positive Long worldId);

    @Operation(
            summary = "완성 세계관 구성 페이지 조회",
            description =
                    "날짜순이며 size는 1~50, 기본 20입니다. nextCursor를 그대로 전달하고 완성본이 바뀌면 409 후 처음부터 조회합니다."
                        + " 완성된 구성만 반환합니다. 삭제로 연결이 비면 connectionGap=true입니다. 원본 수정만으로 저장된 본문이 바뀌지"
                        + " 않습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "구성 조회",
            useReturnTypeSchema = true)
    ResponseEntity<ApiResponse<WorldEntryPage>> page(
            @Parameter(hidden = true) Jwt jwt, @Positive Long worldId, String cursor, int size);

    @Operation(
            summary = "실패한 세계관 작업 재시도",
            description =
                    "실패·만료 작업의 최신 jobVersion으로 명시적으로 재시도합니다. 고정 출처와 이미 성공한 후보 연결을 재사용합니다."
                            + " 폴더·구성·출처·생성 설정이 바뀌었으면 새 생성 또는 부분 반영 요청이 필요합니다. 불확실한 외부 호출을 자동 재전송하지"
                            + " 않습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "202",
            description = "재시도 접수",
            useReturnTypeSchema = true)
    ResponseEntity<ApiResponse<WorldCompositionResponse>> retry(
            @Parameter(hidden = true) Jwt jwt,
            @Positive Long worldId,
            @Valid WorldRetryRequest request);

    @Operation(
            summary = "세계관에 새 서사 부분 반영",
            description =
                    "완성본의 해당 꿈 서사와 앞뒤 연결만 갱신합니다. 다른 꿈에 새 버전이 있어도 기존 버전을 유지합니다. 영향 연결이 모두 성공한 뒤 함께"
                            + " 교체합니다. 같은 서사 버전은 변경 없이 반환합니다. 크레딧을 차감하지 않습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "동일 서사 버전으로 변경 없음",
            useReturnTypeSchema = true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "202",
            description = "부분 반영 접수",
            useReturnTypeSchema = true)
    ResponseEntity<ApiResponse<WorldCompositionResponse>> apply(
            @Parameter(hidden = true) Jwt jwt,
            @Positive Long worldId,
            @Positive Long dreamId,
            @Valid WorldApplyRequest request);
}
