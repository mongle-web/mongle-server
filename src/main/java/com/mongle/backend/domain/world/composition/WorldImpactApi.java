package com.mongle.backend.domain.world.composition;
import com.mongle.backend.global.config.SwaggerConfig;
import com.mongle.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
@Tag(name="World Composition") @SecurityRequirement(name=SwaggerConfig.BEARER_AUTH)
public interface WorldImpactApi {
    @Operation(summary="꿈 영구 삭제의 세계관 영향 조회",description="선택 관계만 있는 폴더와 실제 완성본에서 사용하는 세계관을 함께 조회합니다. 선택 목록에서 제외했어도 완성본 출처이면 포함합니다. ID 내림차순, size 1~50·기본 20입니다. 안내용 조회이며 실제 삭제 시 생성 보호를 재검사하고 관련 구성을 함께 변경합니다. 없는 꿈·타인 꿈은 동일한 404입니다. AI를 호출하지 않습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="영향 세계관 목록",useReturnTypeSchema=true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400",description="페이지 입력 오류")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401",description="인증 필요")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403",description="온보딩 필요")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404",description="없는 꿈 또는 타인 꿈")
    ResponseEntity<ApiResponse<WorldImpactPage>> impacts(@Parameter(hidden=true) Jwt jwt,@Positive Long dreamId,Long beforeId,int size);
}
