package com.mongle.backend.domain.world.composition;

import com.mongle.backend.global.response.ApiResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping(
        value = "/api/v1/worlds/{worldId}/composition",
        produces = MediaType.APPLICATION_JSON_VALUE)
public class WorldCompositionController implements WorldCompositionApi {
    private final WorldCompositionTransactions transactions;

    @Override
    @PostMapping
    public ResponseEntity<ApiResponse<WorldCompositionResponse>> build(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive Long worldId,
            @Valid @RequestBody WorldBuildRequest request) {
        return accepted(transactions.build(user(jwt), worldId, request));
    }

    @Override
    @GetMapping
    public ResponseEntity<ApiResponse<WorldCompositionResponse>> get(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive Long worldId) {
        return ok(transactions.get(user(jwt), worldId));
    }

    @Override
    @GetMapping("/entries")
    public ResponseEntity<ApiResponse<WorldEntryPage>> page(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive Long worldId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        return ok(transactions.page(user(jwt), worldId, cursor, size));
    }

    @Override
    @PostMapping("/retry")
    public ResponseEntity<ApiResponse<WorldCompositionResponse>> retry(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive Long worldId,
            @Valid @RequestBody WorldRetryRequest request) {
        return accepted(transactions.retry(user(jwt), worldId, request.jobVersion()));
    }

    @Override
    @PostMapping("/dreams/{dreamId}/apply")
    public ResponseEntity<ApiResponse<WorldCompositionResponse>> apply(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive Long worldId,
            @PathVariable @Positive Long dreamId,
            @Valid @RequestBody WorldApplyRequest request) {
        var response = transactions.apply(user(jwt), worldId, dreamId, request);
        return response.status() == com.mongle.backend.global.common.GenerationStatus.PENDING
                        || response.status()
                                == com.mongle.backend.global.common.GenerationStatus.PROCESSING
                ? accepted(response)
                : ok(response);
    }

    private Long user(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }

    private <T> ResponseEntity<ApiResponse<T>> accepted(T result) {
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(result));
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T result) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(result));
    }
}
