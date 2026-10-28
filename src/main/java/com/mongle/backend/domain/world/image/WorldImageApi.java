package com.mongle.backend.domain.world.image;

import com.mongle.backend.domain.dream.image.ImageAssetStore;
import com.mongle.backend.global.response.ApiResponse;
import com.mongle.backend.global.config.SwaggerConfig;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.concurrent.CompletableFuture;

@Tag(name="WorldImage",description="세계관 완성본 이미지")
@SecurityRequirement(name=SwaggerConfig.BEARER_AUTH)
public interface WorldImageApi {
    @Operation(summary="세계관 이미지 생성·재생성", description="선택 목록 대신 완성본의 고정 이야기·연결을 사용합니다. compositionRevision 필수. 재생성은 regenerate=true와 최신 imageVersion이 필요합니다. 생성과 재생성은 설정된 크레딧을 사용하며 성공 결과 재사용·진행 중복은 추가 과금하지 않습니다. 새 요청은 비동기 처리 완료 후 200, 동일 진행 요청은 202입니다. 생성 중 구성 변경/삭제/만료는 새 결과 채택을 막고 예약을 해제합니다. 0개 꿈인 완성본은 새 생성 불가, 기존 이미지는 유지합니다.")
    CompletableFuture<ResponseEntity<ApiResponse<WorldImageResponse>>> generate(@Parameter(hidden=true) Jwt jwt,
            @Positive Long worldId,@Valid WorldImageRequest request);
    @Operation(summary="세계관 이미지 상태 조회",description="이전 성공 이미지와 compositionChanged/sourceChanged/sourceDeleted를 확인합니다. sourceDeleted는 삭제 전 구성 이미지 안내에 사용합니다. 기본 비용은 최초 생성 3, 세계관별 첫 재생성 성공 1회 무료, 이후 1크레딧입니다. freeRegenerationAvailable/nextRegenerationCost로 다음 비용을 표시합니다. 구성 변경·꿈 삭제는 무료 이력을 초기화하지 않습니다. 조회는 AI 호출·과금이 없습니다.")
    ResponseEntity<ApiResponse<WorldImageResponse>> get(@Parameter(hidden=true) Jwt jwt,@Positive Long worldId);
    @Operation(summary="세계관 이미지 다운로드 URL",description="본인 세계관의 마지막 성공 이미지에 대해 최대 5분 HTTPS URL을 발급합니다. 재생성 중/실패 및 꿈 삭제 후에도 이전 이미지가 있으면 조회할 수 있습니다.")
    ResponseEntity<ApiResponse<ImageAssetStore.DownloadUrl>> download(@Parameter(hidden=true) Jwt jwt,@Positive Long worldId);
}
