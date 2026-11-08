package com.mongle.backend.domain.payment;

import com.mongle.backend.global.config.SwaggerConfig;
import com.mongle.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/payments/refunds")
@Tag(name = "Refund", description = "본인 유료 크레딧 환불 접수 및 조회")
@SecurityRequirement(name = SwaggerConfig.BEARER_AUTH)
public class RefundController {
    private final RefundService refunds;

    @GetMapping("/estimate")
    @Operation(summary = "본인 주문의 예상 환불액 조회",
            description = "구매별 미사용·예약 수량을 조회합니다. 예약 중이거나 별도 검토가 필요하면 금액은 null입니다.")
    public ResponseEntity<ApiResponse<RefundEstimate>> estimate(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt, @RequestParam UUID orderId,
            @RequestParam(defaultValue = "STANDARD") PaymentRefundRequest.Reason reason) {
        return respond(refunds.estimate(userId(jwt), orderId, reason));
    }

    @PostMapping(consumes = "application/json")
    @Operation(summary = "본인 주문의 환불 신청 접수",
            description = "같은 요청 UUID와 주문·사유를 재전송하면 같은 접수번호를 반환합니다. 대상 구매분을 보류하고 문의 이메일을 반환합니다.")
    public ResponseEntity<ApiResponse<RefundReceipt>> receive(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RefundRequest request) {
        return respond(refunds.receive(userId(jwt), request));
    }

    @GetMapping("/{receiptId}")
    @Operation(summary = "본인 환불 접수 상세 조회")
    public ResponseEntity<ApiResponse<RefundReceipt>> get(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt, @PathVariable UUID receiptId) {
        return respond(refunds.get(userId(jwt), receiptId));
    }

    @GetMapping
    @Operation(summary = "본인 환불 접수 목록 조회", description = "기본 20개, 최대 50개입니다.")
    public ResponseEntity<ApiResponse<RefundPage>> page(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Long beforeId, @RequestParam(defaultValue = "20") int size) {
        return respond(refunds.page(userId(jwt), beforeId, size));
    }

    private static long userId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }

    private static <T> ResponseEntity<ApiResponse<T>> respond(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(data));
    }
}
