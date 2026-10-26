package com.mongle.backend.domain.credit;

import com.mongle.backend.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/api/v1/credits", produces = MediaType.APPLICATION_JSON_VALUE)
public class CreditController implements CreditApi {
    private final CreditService credits;
    @Override @GetMapping("/balance")
    public ResponseEntity<ApiResponse<CreditBalance>> balance(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(credits.balance(Long.valueOf(jwt.getSubject()))));
    }

    @Override @GetMapping("/transactions")
    public ResponseEntity<ApiResponse<CreditTransactionPage>> transactions(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Long beforeId, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(credits.transactions(Long.valueOf(jwt.getSubject()), beforeId, size)));
    }
}
