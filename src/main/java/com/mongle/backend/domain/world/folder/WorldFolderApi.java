package com.mongle.backend.domain.world.folder;

import com.mongle.backend.global.config.SwaggerConfig;
import com.mongle.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

@Tag(name = "World Folder", description = "세계관 폴더와 선택 목록 관리. AI 생성·크레딧 차감 없이 편집합니다.")
@SecurityRequirement(name = SwaggerConfig.BEARER_AUTH)
@ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력 오류: WORLD_400_1 이름 / WORLD_400_2 페이지 / WORLD_400_3 커서. 필수값·양수 검증은 COMMON_400_2, 형식 오류는 COMMON_400_1",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "온보딩 필요",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "WORLD_404_1 폴더 없음·타인 폴더 / DREAM_404_1 꿈 없음·타인 꿈",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "WORLD_409_1 폴더 revision 충돌 / WORLD_409_2 미완성 꿈",
            content = @Content(schema = @Schema(implementation = ApiResponse.class)))
})
public interface WorldFolderApi {
    @Operation(summary = "세계관 폴더 생성", description = "이름은 공백만 입력할 수 없고 최대 100 Unicode 코드포인트입니다. 이름 중복은 허용하며 빈 폴더를 revision=0으로 생성합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성 성공", useReturnTypeSchema = true)
    ResponseEntity<ApiResponse<WorldFolderResponse>> create(@Parameter(hidden = true) Jwt jwt, @Valid WorldFolderRequest request);

    @Operation(summary = "내 세계관 폴더 목록", description = "폴더 ID 내림차순. 기본 size=20, 최대 50. hasNext이면 nextBeforeId를 beforeId에 전달합니다. 선택 수는 전체 선택 목록을 로딩하지 않고 반환합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공", useReturnTypeSchema = true)
    ResponseEntity<ApiResponse<WorldFolderPage>> list(@Parameter(hidden = true) Jwt jwt, Long beforeId, int size);

    @Operation(summary = "세계관 폴더 상세", description = "메타데이터·선택 수·최신 revision을 반환합니다. 선택한 꿈은 별도 페이지 API로 조회합니다. 선택 수가 0~1개여도 유지합니다. 없는 ID와 타인 ID는 동일한 404입니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공", useReturnTypeSchema = true)
    ResponseEntity<ApiResponse<WorldFolderResponse>> get(@Parameter(hidden = true) Jwt jwt, @Positive Long worldId);





}
