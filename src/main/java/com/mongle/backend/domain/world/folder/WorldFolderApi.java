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
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력 오류: WORLD_400_1 이름 / WORLD_400_2 페이지 / WORLD_400_3 커서 / WORLD_400_4 검색어. 필수값·양수 검증은 COMMON_400_2, 형식 오류는 COMMON_400_1",
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

    @Operation(summary = "내 세계관 폴더 목록", description = "폴더 ID 내림차순. 기본 size=20, 최대 50. query는 폴더 이름의 부분 일치이며 영문 대소문자를 구분하지 않습니다. "
            + "%, _, !도 문자 그대로 검색합니다. 앞뒤 Unicode 공백 제외 최대 50 코드포인트, 생략·공백만이면 전체 조회, 초과하면 WORLD_400_4입니다. "
            + "꿈 본문·공통 키워드는 검색하지 않습니다. hasNext이면 같은 query와 nextBeforeId를 beforeId에 전달합니다. 검색어를 바꾸면 beforeId를 제거합니다. "
            + "빈 폴더도 포함하며 선택 수는 전체 선택 목록을 로딩하지 않고 반환합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공", useReturnTypeSchema = true)
    ResponseEntity<ApiResponse<WorldFolderPage>> list(@Parameter(hidden = true) Jwt jwt, Long beforeId, int size,
            @Parameter(description = "폴더 이름 검색어. 앞뒤 공백 제외 최대 50 코드포인트", example = "바다") String query);

    @Operation(summary = "세계관 폴더 상세", description = "메타데이터·선택 수·최신 revision을 반환합니다. 선택한 꿈은 별도 페이지 API로 조회합니다. 선택 수가 0~1개여도 유지합니다. 없는 ID와 타인 ID는 동일한 404입니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공", useReturnTypeSchema = true)
    ResponseEntity<ApiResponse<WorldFolderResponse>> get(@Parameter(hidden = true) Jwt jwt, @Positive Long worldId);

    @Operation(summary = "세계관 폴더 이름 수정", description = "최신 revision 필수. 이름은 최대 100 Unicode 코드포인트이며 앞뒤 공백을 보존합니다. 같은 이름이면 revision·수정 시각을 유지합니다. 이전 revision이면 같은 값도 409입니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정 성공", useReturnTypeSchema = true)
    ResponseEntity<ApiResponse<WorldFolderResponse>> rename(@Parameter(hidden = true) Jwt jwt, @Positive Long worldId, @Valid WorldFolderUpdateRequest request);

    @Operation(summary = "세계관 폴더 삭제", description = "최신 revision을 쿼리로 전달합니다. 폴더·선택 관계·해당 완성본과 후보 작업을 영구 삭제합니다. 원본 꿈·개별 AI 결과·다른 폴더와 공유 연결 결과는 보존합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 성공", useReturnTypeSchema = true)
    ResponseEntity<ApiResponse<Void>> delete(@Parameter(hidden = true) Jwt jwt, @Positive Long worldId, @PositiveOrZero Long revision);

    @Operation(summary = "선택한 꿈 페이지 조회", description = "꿈 날짜·ID 오름차순. size 기본 20, 최대 50이며 선택 총개수 제한은 없습니다. hasNext이면 nextCursor를 cursor로 전달합니다. 커서는 같은 폴더·revision에서만 유효하며 폴더 편집·꿈 삭제 후에는 409로 목록을 처음부터 새로 불러옵니다. 원문·제목은 현재 꿈 값이며 완성 세계관의 스냅샷이 아닙니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공", useReturnTypeSchema = true)
    ResponseEntity<ApiResponse<SelectedDreamPage>> selectedDreams(@Parameter(hidden = true) Jwt jwt, @Positive Long worldId, String cursor, int size);

    @Operation(summary = "세계관에 꿈 선택", description = "내 COMPLETED 기록만 추가합니다. AI 결과 성공 여부와 무관하며 AI 생성 중에도 선택할 수 있습니다. 최신 폴더 revision을 JSON으로 전달합니다. 이미 선택했으면 같은 revision을 유지합니다. 이전 revision은 409입니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "선택 성공", useReturnTypeSchema = true)
    ResponseEntity<ApiResponse<WorldFolderResponse>> select(@Parameter(hidden = true) Jwt jwt, @Positive Long worldId, @Positive Long dreamId, @Valid WorldRevisionRequest request);

    @Operation(summary = "세계관에서 꿈 제외", description = "최신 폴더 revision을 쿼리로 전달합니다. 원본 꿈·개별 AI 결과를 삭제하지 않습니다. 선택되지 않은 내 완성 꿈이면 같은 revision을 유지합니다. 이전 revision은 409입니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "제외 성공", useReturnTypeSchema = true)
    ResponseEntity<ApiResponse<WorldFolderResponse>> exclude(@Parameter(hidden = true) Jwt jwt, @Positive Long worldId, @Positive Long dreamId, @PositiveOrZero Long revision);
}
