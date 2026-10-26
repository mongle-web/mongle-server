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
    private final PaymentQueryService queries;
    @Override @GetMapping("/orders")
    public ResponseEntity<ApiResponse<PaymentOrderPage>> page(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required=false) Long beforeId,@RequestParam(defaultValue="20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(queries.page(Long.valueOf(jwt.getSubject()),beforeId,size)));
    }

    @Override @GetMapping("/orders/{orderId}")
    public ResponseEntity<ApiResponse<PaymentOrderResponse>> get(@AuthenticationPrincipal Jwt jwt,@PathVariable String orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(queries.get(Long.valueOf(jwt.getSubject()),orderId)));
    }

    private final PaymentService payments;
    @Override @PostMapping(value="/orders",consumes=MediaType.APPLICATION_JSON_VALUE)
    public java.util.concurrent.CompletableFuture<ResponseEntity<ApiResponse<PaymentOrderResponse>>> create(
            @AuthenticationPrincipal Jwt jwt,@jakarta.validation.Valid @RequestBody PaymentOrderRequest request) {
        return payments.create(Long.valueOf(jwt.getSubject()),request).thenApply(result ->
                ResponseEntity.status(result.state()==PaymentOrder.State.REQUESTING?202:200).cacheControl(CacheControl.noStore()).body(ApiResponse.success(result)));
    }

    @Override @GetMapping("/products")
    public ResponseEntity<ApiResponse<List<CreditProduct>>> products(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(catalog.products()));
    }
}
