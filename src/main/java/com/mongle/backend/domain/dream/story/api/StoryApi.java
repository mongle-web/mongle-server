package com.mongle.backend.domain.dream.story.api;

import com.mongle.backend.domain.dream.story.StoryRequest;
import com.mongle.backend.domain.dream.story.StoryResponse;
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

import java.util.concurrent.CompletableFuture;

@Tag(name = "DreamStory", description = "꿈 서사화 및 이야기 조회")
@SecurityRequirement(name = SwaggerConfig.BEARER_AUTH)
public interface StoryApi {
    @Operation(
            summary = "완성 꿈 이야기 생성·재생성",
            description =
                    """
                    현재 꿈 revision과 완료된 장면 분석이 필요합니다. 원문과 분석 버전이 다르면 409입니다.
                    regenerate=false이면 완료 결과를 재사용하고 실패·만료 작업은 재시도합니다.
                    regenerate=true이면 조회한 storyVersion도 전달합니다. 오래된 재생성 요청은 409입니다.
                    진행 중 중복 요청은 202, 완료·실패 결과는 200, Gateway 키 미설정은 503입니다.
                    실패는 status와 failureCode로 구분하며 재생성 실패 시 이전 결과를 유지합니다.
                    SCENE은 AI가 다듬은 장면, AI_BRIDGE는 AI가 보완한 연결부입니다.
                    """)
    CompletableFuture<ResponseEntity<ApiResponse<StoryResponse>>> generate(
            @Parameter(hidden = true) Jwt jwt, @Positive Long dreamId, @Valid StoryRequest request);

    @Operation(summary = "꿈의 이야기 조회", description = "이야기가 없거나 타인의 꿈이면 404입니다.")
    ResponseEntity<ApiResponse<StoryResponse>> latest(
            @Parameter(hidden = true) Jwt jwt, @Positive Long dreamId);

    @Operation(
            summary = "내 이야기 단건 조회",
            description =
                    """
                    원문 삭제 후에도 소유자가 조회할 수 있습니다. 타인의 이야기와 없는 이야기는 모두 404입니다.
                    sourceChanged는 표시 중인 성공 결과(없으면 시도)의 원문 버전 변경 여부입니다.
                    hasPreviousResult=true이면 sections는 현재 시도가 아닌 이전 성공 결과입니다.
                    """)
    ResponseEntity<ApiResponse<StoryResponse>> get(
            @Parameter(hidden = true) Jwt jwt, @Positive Long storyId);
}
