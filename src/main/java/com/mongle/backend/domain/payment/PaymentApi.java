package com.mongle.backend.domain.payment;

import com.mongle.backend.global.config.SwaggerConfig;
import com.mongle.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.List;

@Tag(name = "Payment", description = "크레딧 단건 충전 상품과 본인 주문")
@SecurityRequirement(name = SwaggerConfig.BEARER_AUTH)
public interface PaymentApi {
    @Operation(summary = "크레딧 충전 주문 생성", description = "requestId는 결제 버튼 한 번의 UUID이며 재전송 시 유지합니다. 같은 ID의 상품/휴대폰 변경은 409입니다. 결제 요청 결과와 결제 완료는 다르며 지급은 검증된 통보로만 이루어집니다. REQUESTING/UNKNOWN은 결제 요청을 자동 재전송하지 말고 주문을 조회하세요. 설정 미준비는503입니다.")
    java.util.concurrent.CompletableFuture<ResponseEntity<ApiResponse<PaymentOrderResponse>>> create(
            @Parameter(hidden = true) Jwt jwt, @jakarta.validation.Valid PaymentOrderRequest request);

    @Operation(summary = "크레딧 충전 상품 목록", description = "설정된 활성 상품만 반환하며 미설정이면 빈 목록입니다. 상품 노출은 실결제 준비 완료를 뜻하지 않습니다. 가격·지급량을 클라이언트에서 결정하지 않습니다.")
    ResponseEntity<ApiResponse<List<CreditProduct>>> products(@Parameter(hidden = true) Jwt jwt);
}
