package com.mongle.backend.domain.world.composition;
import com.mongle.backend.global.config.SwaggerConfig;
import com.mongle.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
@Tag(name="World Composition",description="World composition generation and fixed successful story versions. No credit charge.")
@SecurityRequirement(name=SwaggerConfig.BEARER_AUTH)
@ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400",description="Invalid input or composition cursor"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401",description="Authentication required"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403",description="Onboarding required"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404",description="Missing or foreign folder/source"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409",description="Revision conflict, insufficient/current stories, active job or retry required"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="503",description="COMPOSITION_503_1 generation disabled")})
public interface WorldCompositionApi {
    @Operation(summary="Create world composition",description="At least two selected dreams with current successful stories. Frozen versions are ordered by date. POST only reserves a durable job; poll composition status. Same active request reuses the job. Any failure preserves the latest successful composition. No credit charge.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="202",description="Job accepted",useReturnTypeSchema=true)
    ResponseEntity<ApiResponse<WorldCompositionResponse>> build(@Parameter(hidden=true) Jwt jwt,@Positive Long worldId,@Valid WorldBuildRequest request);
    @Operation(summary="Get world composition status",description="Returns separate folder/composition revisions and jobVersion. No AI generation on GET. hasResult may be true during a new failed/in-progress job. attemptId=null means no request yet.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="Status",useReturnTypeSchema=true)
    ResponseEntity<ApiResponse<WorldCompositionResponse>> get(@Parameter(hidden=true) Jwt jwt,@Positive Long worldId);
    @Operation(summary="Get completed composition entries",description="Date order, size1..50 (default20). Use opaque nextCursor; composition change invalidates it with409. Only completed entries are visible. Missing bridges after deletion are connectionGap=true. Source changes do not rewrite the fixed text.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="Entries",useReturnTypeSchema=true)
    ResponseEntity<ApiResponse<WorldEntryPage>> page(@Parameter(hidden=true) Jwt jwt,@Positive Long worldId,String cursor,int size);
    @Operation(summary="Retry failed world composition",description="Explicitly retries a failed/expired job with its latest jobVersion, preserving frozen inputs and completed bridges. Changed folder/composition/source/config requires a new request. No automatic paid external retry.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="202",description="Retry accepted",useReturnTypeSchema=true)
    ResponseEntity<ApiResponse<WorldCompositionResponse>> retry(@Parameter(hidden=true) Jwt jwt,@Positive Long worldId,@Valid WorldRetryRequest request);
    @Operation(summary="Apply a new story version",description="Explicitly updates one completed composition's story and only its adjacent bridges. Other story/bridge versions stay fixed, even if newer versions exist. All affected bridges must succeed before publishing. Same version is a no-op. No credit charge.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="Same story version, unchanged",useReturnTypeSchema=true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="202",description="Partial update accepted",useReturnTypeSchema=true)
    ResponseEntity<ApiResponse<WorldCompositionResponse>> apply(@Parameter(hidden=true) Jwt jwt,@Positive Long worldId,@Positive Long dreamId,@Valid WorldApplyRequest request);

}
