package com.mongle.backend.domain.credit;
import java.util.List;
public record CreditTransactionPage(List<CreditTransaction> items, boolean hasNext, Long nextBeforeId) {}
