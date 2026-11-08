package com.mongle.backend.domain.payment;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record RefundReceipt(
        String receiptId,
        @Schema(description = "원래 결제 주문의 공개 UUID. 환불 처리 상태와 관계없이 결제 주문 조회의 orderId와 같습니다.",
                format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
        String orderId,
        String requestId, Instant receivedAt, PaymentRefundRequest.Reason reason,
        RefundEstimate.Status initialStatus, long unusedAtRequest, long reservedAtRequest, long usedAtRequest,
        Long estimatedAmountAtRequest, Integer feePercentAtRequest, RefundEstimate currentEstimate,
        String supportEmail, ProcessingStatus processingStatus, Resolution resolution) {

    public enum ProcessingStatus { RECEIVED, COMPLETED, REJECTED, WITHDRAWN }

    public record Resolution(long refundedAmount, long revokedCredits, Instant resolvedAt) {}
}
