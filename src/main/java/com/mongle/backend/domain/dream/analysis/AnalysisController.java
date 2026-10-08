package com.mongle.backend.domain.dream.analysis;

import com.mongle.backend.domain.dream.analysis.api.AnalysisApi;
import com.mongle.backend.domain.dream.dto.DreamRevisionRequest;
import com.mongle.backend.global.common.GenerationStatus;
import com.mongle.backend.global.response.ApiResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import lombok.RequiredArgsConstructor;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class AnalysisController implements AnalysisApi {
    private final DreamStructureService service;
    private final AnalysisTransactions transactions;

    @Override
    @PostMapping("/dreams/{dreamId}/analysis")
    public CompletableFuture<ResponseEntity<ApiResponse<AnalysisResponse>>> analyze(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive Long dreamId,
            @Valid @RequestBody DreamRevisionRequest request) {
        return service.analyze(Long.valueOf(jwt.getSubject()), dreamId, request.revision())
                .thenApply(
                        result -> {
                            var status =
                                    result.status() == GenerationStatus.PROCESSING
                                            ? HttpStatus.ACCEPTED
                                            : HttpStatus.OK;
                            return ResponseEntity.status(status)
                                    .cacheControl(CacheControl.noStore())
                                    .body(ApiResponse.success(result));
                        });
    }

    @Override
    @GetMapping("/analyses/{analysisId}")
    public ResponseEntity<ApiResponse<AnalysisResponse>> get(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive Long analysisId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        ApiResponse.success(
                                transactions.get(Long.valueOf(jwt.getSubject()), analysisId)));
    }
}
