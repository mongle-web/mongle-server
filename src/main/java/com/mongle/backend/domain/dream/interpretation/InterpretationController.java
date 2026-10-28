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
    @Override @GetMapping
    public ResponseEntity<ApiResponse<InterpretationResponse>> get(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(transactions.get(Long.valueOf(jwt.getSubject()))));
    }
}
