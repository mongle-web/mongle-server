package com.mongle.backend.domain.dream.image.api;

import com.mongle.backend.domain.dream.image.*;
import com.mongle.backend.global.config.SwaggerConfig;
import com.mongle.backend.global.response.ApiResponse;

import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import java.util.concurrent.CompletableFuture;

import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

@Tag(name = "DreamImage", description = "꿈 이미지 생성 및 보존 결과 조회")
@SecurityRequirement(name = SwaggerConfig.BEARER_AUTH)
public interface ImageApi {
    @Operation(
            summary = "허용 이미지 옵션 조회",
            description = "운영 설정의 허용 옵션을 반환합니다. 빈 styles이면 생성 기능이 준비되지 않았습니다.")
    ResponseEntity<ApiResponse<ImageOptions>> options(@Parameter(hidden = true) Jwt jwt);

    @Operation(
            summary = "꿈 이미지 생성·재생성",
            description =
                    """
                    생성 응답은 비동기로 완료되며 외부 대기 동안 요청 스레드를 점유하지 않습니다.
                    최신 꿈 revision과 현재 원문으로 완료한 분석·이야기가 필요합니다.
                    중복 요청은 진행 중 202, 완료 결과 재사용은 200입니다. 옵션·이야기 변경은 재생성이 필요합니다.
                    regenerate=true일 때 조회한 imageVersion도 전달합니다. 오래된 요청은 409입니다.
                    새 작업의 인스턴스 처리 한도 초과는 IMAGE_503_2입니다. 기존 결과 재사용은 한도와 무관합니다.
                    Provider/스토리지/스타일 미설정은 503입니다. 처리 실패는 status=FAILED와 failureCode로 표시합니다.
                    Provider 실패는 인증/요청 제한/시간 초과/연결 불가/요청 거절로 분류하며 자동 재호출하지 않습니다.
                    새 생성은 크레딧을 예약하고 성공 이미지 저장과 함께 차감을 확정합니다. 실패·만료 시 예약을 해제합니다.
                    기존 결과 조회·재사용과 진행 요청 중복은 추가 차감하지 않습니다. 명시적 재생성은 새 생성으로 과금합니다.
                    재생성 실패 시 이전 성공 결과를 유지합니다. 생성 도중 결제 사용 보류 시 CREDIT_PAYMENT_REVIEW로 실패합니다.
                    """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200", description="완료 또는 실패 상태, 기존 결과 재사용", useReturnTypeSchema=true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="202", description="동일 이미지 생성 진행 중", useReturnTypeSchema=true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409", description="버전/재생성 충돌 또는 CREDIT_409_1 잔액 부족, CREDIT_409_5 결제 확인 중")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="503", description="Provider/저장소/옵션 미준비, IMAGE_503_2 처리 한도, CREDIT_503_1 과금 비활성 또는 사용량 미설정")
    CompletableFuture<ResponseEntity<ApiResponse<ImageResponse>>> generate(
            @Parameter(hidden = true) Jwt jwt, @Positive Long dreamId, @Valid ImageRequest request);

    @Operation(summary = "꿈의 이미지 상태 조회")
    ResponseEntity<ApiResponse<ImageResponse>> latest(
            @Parameter(hidden = true) Jwt jwt, @Positive Long dreamId);

    @Operation(
            summary = "내 이미지 단건 조회",
            description =
                    """
                    꿈 삭제 시 이미지 기록도 제거합니다. 삭제·타인·없는 결과는 모두 404입니다.
                    hasPreviousResult=true이면 MIME·크기·resultStyle/resultMood는 이전 성공 결과입니다.
                    sourceChanged/storyChanged는 표시 중인 결과의 입력 변경 여부입니다. 저장 키와 URL은 포함하지 않습니다.
                    """)
    ResponseEntity<ApiResponse<ImageResponse>> get(
            @Parameter(hidden = true) Jwt jwt, @Positive Long imageId);

    @Operation(
            summary = "내 이미지 임시 다운로드 URL 발급",
            description = "소유권 확인 후 HTTPS URL을 최대 5분간 발급합니다. 꿈 삭제 후 새 URL 발급은 404이며 응답을 캐시하지 않습니다. 이미 발급한 URL은 만료 전까지 유효할 수 있습니다.")
    ResponseEntity<ApiResponse<ImageAssetStore.DownloadUrl>> downloadUrl(
            @Parameter(hidden = true) Jwt jwt, @Positive Long imageId);
}
