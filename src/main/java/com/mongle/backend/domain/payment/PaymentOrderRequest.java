package com.mongle.backend.domain.payment;
import jakarta.validation.constraints.*;
import java.util.UUID;
public record PaymentOrderRequest(@NotNull UUID requestId, @NotBlank String productId,
        @NotBlank @Pattern(regexp="01[016789][0-9]{7,8}", message="국내 휴대폰번호를 숫자로 입력해주세요.") String buyerPhone) {
    @Override public String toString() { return "PaymentOrderRequest[redacted]"; }
}
