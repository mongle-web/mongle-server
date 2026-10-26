package com.mongle.backend.domain.world.composition;
import com.mongle.backend.global.response.ApiResponse;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor
@RequestMapping(value="/api/v1/dreams/{dreamId}/world-impacts",produces=MediaType.APPLICATION_JSON_VALUE)
public class WorldImpactController implements WorldImpactApi {
    private final WorldImpactService service;
    @Override @GetMapping public ResponseEntity<ApiResponse<WorldImpactPage>> impacts(@AuthenticationPrincipal Jwt jwt,@PathVariable @Positive Long dreamId,
            @RequestParam(required=false) Long beforeId,@RequestParam(defaultValue="20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.impacts(Long.valueOf(jwt.getSubject()),dreamId,beforeId,size)));
    }
}
