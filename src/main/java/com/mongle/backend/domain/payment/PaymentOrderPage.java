package com.mongle.backend.domain.payment;
import java.util.List;
public record PaymentOrderPage(List<PaymentOrderResponse> items,boolean hasNext,Long nextBeforeId) {}
