package com.mongle.backend.domain.payment;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;

public record RefundEstimate(
        String orderId, long purchaseAmount, String currency, long purchasedCredits,
        long usedCredits, long reservedCredits, long unusedCredits, Instant approvedAt,
        boolean requestable, Status status, Long estimatedAmount, Integer feePercent) {

    public enum Status { ESTIMATED, WAITING_RESERVATION, REVIEW_REQUIRED, NO_REFUNDABLE_BALANCE }

    static RefundEstimate calculate(PaymentOrder order, com.mongle.backend.domain.credit.CreditGrant grant,
            PaymentRefundRequest.Reason reason, Instant evaluatedAt) {
        long available = grant.getAvailable();
        long reserved = grant.getReserved();
        boolean review = reason == PaymentRefundRequest.Reason.RIGHTS_REVIEW
                || order.getState() == PaymentOrder.State.PAYMENT_REVIEW
                || order.getApprovedAt() == null || order.getApprovedAt().isAfter(evaluatedAt);
        boolean requestable = available > 0 || reserved > 0 || reason == PaymentRefundRequest.Reason.RIGHTS_REVIEW;
        Status status;
        Long amount = null;
        Integer fee = null;
        if (!requestable) {
            status = Status.NO_REFUNDABLE_BALANCE;
        } else if (reserved > 0) {
            status = Status.WAITING_RESERVATION;
        } else if (review) {
            status = Status.REVIEW_REQUIRED;
        } else {
            status = Status.ESTIMATED;
            fee = standardFeePercent(order.getApprovedAt(), evaluatedAt);
            amount = standardAmount(order.getPrice(), order.getCredits(), available, fee);
        }
        return new RefundEstimate(order.getPublicId(), order.getPrice(), order.getCurrency(), order.getCredits(),
                grant.getConsumed(), reserved, available, order.getApprovedAt(), requestable, status, amount, fee);
    }

    public static int standardFeePercent(Instant approvedAt, Instant requestedAt) {
        return requestedAt.isAfter(approvedAt.plus(Duration.ofDays(7))) ? 10 : 0;
    }

    public static long standardAmount(long price, long purchased, long unused, int feePercent) {
        // Round once at the end, up to a whole won.
        var numerator = BigInteger.valueOf(price).multiply(BigInteger.valueOf(unused))
                .multiply(BigInteger.valueOf(100 - feePercent));
        var divisor = BigInteger.valueOf(purchased).multiply(BigInteger.valueOf(100));
        return numerator.add(divisor).subtract(BigInteger.ONE).divide(divisor).longValueExact();
    }
}
