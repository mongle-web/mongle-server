package com.mongle.backend.domain.payment;

import java.util.List;

public record RefundPage(List<RefundReceipt> items, boolean hasNext, Long nextBeforeId) {
}
