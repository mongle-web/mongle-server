package com.mongle.backend.domain.dream.image.api;

import com.mongle.backend.domain.dream.image.*;
import com.mongle.backend.global.config.SwaggerConfig;
import com.mongle.backend.global.response.ApiResponse;

import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

@Tag(name = "DreamImage", description = "꿈 이미지 생성 및 보존 결과 조회")
@SecurityRequirement(name = SwaggerConfig.BEARER_AUTH)
public interface ImageDownloadApi {
    @Operation(
            summary = "내 꿈 이미지 임시 URL 일괄 발급",
            description =
                    """
                    중복 제거 전 1~31개의 양수 imageIds가 필요합니다. 중복은 최초 순서로 한 번만 반환합니다.
                    HTTP 200 안의 items[].status와 failureCode를 항목별로 확인합니다. 전체 실패여도 200입니다.
                    타인·없는 이미지·삭제·성공 파일 없음은 모두 FAILED/IMAGE_404_1이며 imageVersion은 null입니다.
                    재생성 진행/실패 중에도 보존된 성공 파일이 있으면 발급합니다. 일부 발급 실패는 다른 항목에 영향을 주지 않습니다.
                    SUCCESS 항목의 imageVersion은 조회 시점의 스냅샷 버전이며 url/expiresAt을 제공합니다.
                    저장소 미설정은 IMAGE_503_1, 서명·URL 검증 실패는 IMAGE_502_2입니다. 실패 시 url/expiresAt은 null입니다.
                    HTTPS·최대 5분·Cache-Control:no-store를 유지하며 AI 호출이나 과금은 없습니다.
                    서명 중 DB 트랜잭션·잠금을 유지하지 않습니다. 배치 내 순차 처리, 인스턴스 동시 배치 수 제한을 적용합니다.
                    조회 후 삭제·재생성하면 이미 발급된 URL은 만료 전까지 유효하거나 파일 정리로 먼저 사용할 수 없게 될 수 있습니다.
                    반환 URL은 원본 파일 주소이며 작은 썸네일 파일을 생성하지 않습니다. 세계관 이미지 ID는 대상이 아닙니다.
                    """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "항목별 성공·실패 결과",
            useReturnTypeSchema = true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "배열 1~31개, null·0·음수 ID 불가")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "503",
            description = "IMAGE_503_2 동시 배치 한도 초과. 잠시 후 재시도")
    ResponseEntity<ApiResponse<ImageDownloadResponse>> downloadUrls(
            @Parameter(hidden = true) Jwt jwt, @Valid ImageDownloadRequest request);
}
