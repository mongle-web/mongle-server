package com.mongle.backend.domain.payment;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record RefundRequest(@NotNull UUID requestId, @NotNull UUID orderId, @NotNull PaymentRefundRequest.Reason reason) {
}
