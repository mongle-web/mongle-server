package com.mongle.backend.domain.world.summary;

import com.mongle.backend.global.response.ApiResponse;

import jakarta.validation.constraints.Positive;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/v1/worlds/{worldId}/summary")
public class WorldSummaryController implements WorldSummaryApi {
    private final WorldSummaryTransactions transactions;

    @Override
    @GetMapping
    public ResponseEntity<ApiResponse<WorldSummaryResponse>> get(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive Long worldId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        ApiResponse.success(
                                transactions.get(Long.valueOf(jwt.getSubject()), worldId)));
    }

    @Override
    @PostMapping
    public ResponseEntity<ApiResponse<WorldSummaryResponse>> request(
            @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive Long worldId) {
        var response = transactions.request(Long.valueOf(jwt.getSubject()), worldId);
        var status = response.status();
        return ResponseEntity.status(
                        status == WorldSummaryResponse.Status.PENDING
                                        || status == WorldSummaryResponse.Status.PROCESSING
                                ? HttpStatus.ACCEPTED
                                : HttpStatus.OK)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(response));
    }
}
