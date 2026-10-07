package com.mongle.backend.domain.dream.controller;

import com.mongle.backend.domain.dream.controller.api.DreamApi;
import com.mongle.backend.domain.dream.dto.*;
import com.mongle.backend.domain.dream.service.DreamService;
import com.mongle.backend.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/api/v1/dreams", produces = MediaType.APPLICATION_JSON_VALUE)
public class DreamController implements DreamApi {
    private final DreamService dreams;

    @Override
    @PostMapping
    public ResponseEntity<ApiResponse<DreamResponse>> create(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DreamCreateRequest request) {
        var result = dreams.create(userId(jwt), request);
        return ResponseEntity.created(URI.create("/api/v1/dreams/" + result.dreamId()))
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(result));
    }

    @Override
    @PutMapping("/drafts/{dreamedAt}")
    public ResponseEntity<ApiResponse<DreamResponse>> saveDraft(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dreamedAt,
            @Valid @RequestBody DreamDraftRequest request) {
        return ok(dreams.saveDraft(userId(jwt), dreamedAt, request));
    }

    @Override
    @PostMapping("/{dreamId}/submit")
    public ResponseEntity<ApiResponse<DreamResponse>> submit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive(message = "꿈 ID는 양수여야 합니다.") Long dreamId,
            @Valid @RequestBody DreamRevisionRequest request) {
        return ok(dreams.submit(userId(jwt), dreamId, request.revision()));
    }

    @Override
    @PutMapping("/{dreamId}/emotions")
    public ResponseEntity<ApiResponse<DreamResponse>> complete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive(message = "꿈 ID는 양수여야 합니다.") Long dreamId,
            @Valid @RequestBody DreamEmotionsRequest request) {
        return ok(dreams.complete(userId(jwt), dreamId, request));
    }

    @Override
    @GetMapping("/incomplete")
    public ResponseEntity<ApiResponse<DreamService.IncompleteDreams>> incomplete(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "페이지는 0 이상이어야 합니다.")
                    int page,
            @RequestParam(defaultValue = "20")
                    @Min(value = 1, message = "조회 개수는 1 이상이어야 합니다.")
                    @Max(value = 50, message = "조회 개수는 최대 50개입니다.")
                    int size) {
        return ok(dreams.incomplete(userId(jwt), page, size));
    }

    @Override
    @GetMapping("/{dreamId}")
    public ResponseEntity<ApiResponse<DreamResponse>> get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive(message = "꿈 ID는 양수여야 합니다.") Long dreamId) {
        return ok(dreams.get(userId(jwt), dreamId));
    }

    @Override
    @PatchMapping("/{dreamId}")
    public ResponseEntity<ApiResponse<DreamResponse>> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive(message = "꿈 ID는 양수여야 합니다.") Long dreamId,
            @Valid @RequestBody DreamUpdateRequest request) {
        return ok(dreams.update(userId(jwt), dreamId, request));
    }

    @Override
    @DeleteMapping("/{dreamId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive(message = "꿈 ID는 양수여야 합니다.") Long dreamId,
            @RequestParam @PositiveOrZero(message = "버전은 0 이상이어야 합니다.") Long revision) {
        dreams.delete(userId(jwt), dreamId, revision);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.emptySuccess());
    }

    private Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T result) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(result));
    }
}
