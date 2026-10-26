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
            description = "옵션은 PM 확정 후 설정합니다. 빈 styles이면 생성 기능이 준비되지 않았습니다.")
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
                    재생성 실패 시 이전 성공 결과를 유지합니다. 결제·크레딧 차감은 포함하지 않습니다.
                    """)
    CompletableFuture<ResponseEntity<ApiResponse<ImageResponse>>> generate(
            @Parameter(hidden = true) Jwt jwt, @Positive Long dreamId, @Valid ImageRequest request);

    @Operation(summary = "꿈의 이미지 상태 조회")
    ResponseEntity<ApiResponse<ImageResponse>> latest(
            @Parameter(hidden = true) Jwt jwt, @Positive Long dreamId);

    @Operation(
            summary = "내 이미지 단건 조회",
            description =
                    """
                    원문 삭제 후에도 완료 결과를 보존합니다. 타인 결과와 없는 결과는 모두 404입니다.
                    hasPreviousResult=true이면 MIME·크기·resultStyle/resultMood는 이전 성공 결과입니다.
                    sourceChanged/storyChanged는 표시 중인 결과의 입력 변경 여부입니다. 저장 키와 URL은 포함하지 않습니다.
                    """)
    ResponseEntity<ApiResponse<ImageResponse>> get(
            @Parameter(hidden = true) Jwt jwt, @Positive Long imageId);

    @Operation(
            summary = "내 이미지 임시 다운로드 URL 발급",
            description = "소유권 확인 후 HTTPS URL을 최대 5분간 발급합니다. 원문 삭제 후에도 사용 가능하며 응답을 캐시하지 않습니다.")
    ResponseEntity<ApiResponse<ImageAssetStore.DownloadUrl>> downloadUrl(
            @Parameter(hidden = true) Jwt jwt, @Positive Long imageId);
}
