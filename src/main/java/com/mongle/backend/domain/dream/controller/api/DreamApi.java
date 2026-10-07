package com.mongle.backend.domain.dream.controller.api;

import com.mongle.backend.domain.dream.dto.*;
import com.mongle.backend.domain.dream.service.DreamService;
import com.mongle.backend.global.config.SwaggerConfig;
import com.mongle.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import java.time.LocalDate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

@Tag(name = "Dream", description = "꿈 기록·자동저장·미완성 기록 복구")
@SecurityRequirement(name = SwaggerConfig.BEARER_AUTH)
public interface DreamApi {
    @Operation(
            summary = "꿈 원문 등록",
            description =
                    "자동저장 없이 다음을 누를 때 사용합니다. 원문 최대 500자, 한국 날짜 기준 미래 불가, 날짜당 1개입니다. 제목 없이 EMOTION_PENDING으로 저장합니다. 이미 초안이 있으면 submit을 호출해주세요.")
    ResponseEntity<ApiResponse<DreamResponse>> create(
            @Parameter(hidden = true) Jwt jwt, @Valid DreamCreateRequest request);

    @Operation(
            summary = "입력 중 꿈 자동저장",
            description =
                    "최초 저장에는 dreamId와 revision을 생략하고 빈 원문도 허용합니다. 이후 응답의 dreamId·revision을 다음 요청에 전달합니다. DRAFT와 EMOTION_PENDING만 수정하며 날짜는 최초 저장 이후 변경할 수 없습니다. 최초 요청 재시도 중 409가 오면 미완성 목록에서 복구해주세요.")
    ResponseEntity<ApiResponse<DreamResponse>> saveDraft(
            @Parameter(hidden = true) Jwt jwt,
            LocalDate dreamedAt,
            @Valid DreamDraftRequest request);

    @Operation(
            summary = "원문 작성 완료",
            description =
                    "최신 revision을 전달합니다. 저장된 초안의 원문을 검증한 뒤 EMOTION_PENDING으로 이동합니다. 자동저장 응답을 받은 후 호출해주세요.")
    ResponseEntity<ApiResponse<DreamResponse>> submit(
            @Parameter(hidden = true) Jwt jwt,
            @Positive(message = "꿈 ID는 양수여야 합니다.") Long dreamId,
            @Valid DreamRevisionRequest request);

    @Operation(
            summary = "감정 선택 및 기록 완성",
            description =
                    "HAPPY(행복), CALM(편안함), EXCITED(설렘), SAD(슬픔), ANXIOUS(불안), ANGRY(화남), CONFUSED(당황) 중 중복 없이 1~3개를 선택합니다. 대표 감정과 순위는 없습니다. EMOTION_PENDING에서 COMPLETED로 이동합니다. AI 호출은 별도 단계입니다.")
    ResponseEntity<ApiResponse<DreamResponse>> complete(
            @Parameter(hidden = true) Jwt jwt,
            @Positive(message = "꿈 ID는 양수여야 합니다.") Long dreamId,
            @Valid DreamEmotionsRequest request);

    @Operation(
            summary = "미완성 꿈 복구 목록",
            description =
                    "DRAFT와 EMOTION_PENDING을 날짜 내림차순으로 조회합니다. items의 dreamedAt·recordStatus로 안내 모달과 복구할 화면을 정합니다. 기본 20개, 최대 50개이며 hasNext이면 다음 페이지를 조회합니다.")
    ResponseEntity<ApiResponse<DreamService.IncompleteDreams>> incomplete(
            @Parameter(hidden = true) Jwt jwt,
            @Min(value = 0, message = "페이지는 0 이상이어야 합니다.") int page,
            @Min(value = 1, message = "조회 개수는 1 이상이어야 합니다.")
                    @Max(value = 50, message = "조회 개수는 최대 50개입니다.")
                    int size);

    @Operation(
            summary = "내 꿈 단건 조회",
            description = "타인의 기록과 없는 기록은 모두 404입니다. 원문·감정·작성 상태·수정 표시·최신 revision을 반환합니다.")
    ResponseEntity<ApiResponse<DreamResponse>> get(
            @Parameter(hidden = true) Jwt jwt, @Positive(message = "꿈 ID는 양수여야 합니다.") Long dreamId);

    @Operation(
            summary = "완성한 꿈 수정",
            description =
                    "revision 필수. 미전달 항목은 유지합니다. originalText·emotions에 null은 불가하며 title:null은 제목 제거입니다. 제목은 최대 100자로 설정했습니다. 날짜 및 사용자 ID는 변경할 수 없습니다. 기존 분석은 유지하며 실제 변경 시 edited=true입니다.")
    ResponseEntity<ApiResponse<DreamResponse>> update(
            @Parameter(hidden = true) Jwt jwt,
            @Positive(message = "꿈 ID는 양수여야 합니다.") Long dreamId,
            @Valid DreamUpdateRequest request);

    @Operation(
            summary = "내 꿈 삭제",
            description =
                    "최신 revision을 쿼리로 전달합니다. 원문과 선택 감정은 영구 삭제하며 복구할 수 없습니다. 기존 분석 장면·요소와 둘의 연결은 유지하되 꿈 FK를 해제합니다. 이미지·세계관 모델 추가 시 별도 삭제 처리를 연결해야 합니다.")
    ResponseEntity<ApiResponse<Void>> delete(
            @Parameter(hidden = true) Jwt jwt,
            @Positive(message = "꿈 ID는 양수여야 합니다.") Long dreamId,
            @PositiveOrZero(message = "버전은 0 이상이어야 합니다.") Long revision);
}
