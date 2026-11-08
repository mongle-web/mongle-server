package com.mongle.backend.domain.payment;

/** Trusted original notification and a separate confirmation from the seller portal. */
public record PaymentRecoveryEvidence(String originalForm, String confirmedTransaction, int confirmedState,
        long confirmedAmount) {
    @Override
    public String toString() { return "PaymentRecoveryEvidence[redacted]"; }
}
