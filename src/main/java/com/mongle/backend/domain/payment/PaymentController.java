package com.mongle.backend.domain.payment;

import com.mongle.backend.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/api/v1/payments", produces = MediaType.APPLICATION_JSON_VALUE)
public class PaymentController implements PaymentApi {
    private final PaymentCatalog catalog;
    @Override @GetMapping("/products")
    public ResponseEntity<ApiResponse<List<CreditProduct>>> products(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(catalog.products()));
    }
}
