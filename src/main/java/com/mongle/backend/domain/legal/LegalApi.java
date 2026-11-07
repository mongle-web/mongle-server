package com.mongle.backend.domain.legal;

import com.mongle.backend.domain.user.dto.UserResponse;
import com.mongle.backend.global.config.SwaggerConfig;
import com.mongle.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

@Tag(name = "Legal", description = "버전별 필수 문서 동의")
public interface LegalApi {
    @Operation(summary = "필수 동의 문서 버전·해시 조회",
            description = "공개 조회입니다. published=false이면 동의를 접수하지 않습니다. 프론트는 published=true인 현재 버전·해시와 동일한 문서를 각각 표시해야 합니다. 본문을 반환하는 API는 아니며 PRIVACY는 필수 개인정보 수집·이용 고지입니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "문서 메타데이터", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "LEGAL_503_1 문서 미준비, LEGAL_503_2 동일 버전의 해시 충돌")
    })
    ResponseEntity<ApiResponse<LegalService.Catalog>> documents();

    @Operation(summary = "이용약관·개인정보 필수 수집·이용 동의",
            description = "온보딩 전/기존 회원 모두 가능합니다. 두 필수 항목·만14세 이상 확인·현재 문서 버전과 SHA-256이 필요합니다. 시각은 서버 UTC로 기록하며 동일 버전 재요청은 최초 시각을 보존합니다. 회원 ID나 동의 시각을 요청에 넣지 않습니다.")
    @SecurityRequirement(name = SwaggerConfig.BEARER_AUTH)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "갱신된 회원 상태", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "LEGAL_400_1 필수 동의/연령 확인 누락 또는 거절"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "LEGAL_409_1 요청한 문서 버전·해시가 현재와 다름"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "LEGAL_503_1 공개 전/문서 미준비, LEGAL_503_2 동일 버전의 해시 충돌")
    })
    ResponseEntity<ApiResponse<UserResponse>> accept(
            @Parameter(hidden = true) Jwt jwt, LegalService.Accept request);
}
