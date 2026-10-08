package com.mongle.backend.domain.dream.generation;

import com.mongle.backend.domain.dream.dto.DreamRevisionRequest;
import com.mongle.backend.global.response.ApiResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import lombok.RequiredArgsConstructor;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/dreams/{dreamId}/generation")
public class DreamGenerationController implements DreamGenerationApi {
    private final DreamGenerationTransactions transactions;

    @Override
    @GetMapping
    public ResponseEntity<ApiResponse<DreamGenerationResponse>> get(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive Long dreamId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(transactions.get(Long.valueOf(jwt.getSubject()), dreamId)));
    }

    @Override
    @PostMapping("/retry")
    public ResponseEntity<ApiResponse<DreamGenerationResponse>> retry(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive Long dreamId,
            @Valid @RequestBody DreamRevisionRequest request) {
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(
                        transactions.retry(Long.valueOf(jwt.getSubject()), dreamId, request.revision())));
    }
}
