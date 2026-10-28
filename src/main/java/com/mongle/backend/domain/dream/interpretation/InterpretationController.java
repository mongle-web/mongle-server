package com.mongle.backend.domain.dream.interpretation;

import com.mongle.backend.domain.dream.interpretation.api.InterpretationApi;
import com.mongle.backend.global.response.ApiResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/analysis-records/interpretation")
public class InterpretationController implements InterpretationApi {
    private final InterpretationTransactions transactions;
    private final InterpretationService service;

    @Override
    @PostMapping
    public java.util.concurrent.CompletableFuture<
                    ResponseEntity<ApiResponse<InterpretationResponse>>>
            ensure(@AuthenticationPrincipal Jwt jwt) {
        return service.ensure(Long.valueOf(jwt.getSubject())).thenApply(this::result);
    }

    @Override
    @PostMapping("/refresh")
    public java.util.concurrent.CompletableFuture<
                    ResponseEntity<ApiResponse<InterpretationResponse>>>
            refresh(@AuthenticationPrincipal Jwt jwt) {
        return service.refresh(Long.valueOf(jwt.getSubject())).thenApply(this::result);
    }

    private ResponseEntity<ApiResponse<InterpretationResponse>> result(
            InterpretationResponse response) {
        return ResponseEntity.status(
                        response.status() == InterpretationResponse.State.PROCESSING
                                ? HttpStatus.ACCEPTED
                                : HttpStatus.OK)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(response));
    }

    @Override
    @GetMapping
    public ResponseEntity<ApiResponse<InterpretationResponse>> get(
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(transactions.get(Long.valueOf(jwt.getSubject()))));
    }
}
