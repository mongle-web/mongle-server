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
            fee = evaluatedAt.isAfter(order.getApprovedAt().plus(Duration.ofDays(7))) ? 10 : 0;
            // Round once at the end, up to a whole won. BigInteger also supports large credit quantities.
            var numerator = BigInteger.valueOf(order.getPrice()).multiply(BigInteger.valueOf(available))
                    .multiply(BigInteger.valueOf(100 - fee));
            var divisor = BigInteger.valueOf(order.getCredits()).multiply(BigInteger.valueOf(100));
            amount = numerator.add(divisor).subtract(BigInteger.ONE).divide(divisor).longValueExact();
        }
        return new RefundEstimate(order.getPublicId(), order.getPrice(), order.getCurrency(), order.getCredits(),
                grant.getConsumed(), reserved, available, order.getApprovedAt(), requestable, status, amount, fee);
    }
}
