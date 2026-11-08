package com.mongle.backend.domain.payment;

import java.time.Instant;

public record RefundReceipt(
        String receiptId, String requestId, Instant receivedAt, PaymentRefundRequest.Reason reason,
        RefundEstimate.Status initialStatus, long unusedAtRequest, long reservedAtRequest, long usedAtRequest,
        Long estimatedAmountAtRequest, Integer feePercentAtRequest, RefundEstimate currentEstimate,
        String supportEmail, ProcessingStatus processingStatus, Resolution resolution) {

    public enum ProcessingStatus { RECEIVED, COMPLETED, REJECTED, WITHDRAWN }

    public record Resolution(long refundedAmount, long revokedCredits, Instant resolvedAt) {}
}
