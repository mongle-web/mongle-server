package com.mongle.backend.domain.credit;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "불변 크레딧 원장 내역. delta는 증감량, after는 해당 내역 직후 잔액입니다. 결제사 거래 키·내부 요청 키는 노출하지 않습니다.")
public record CreditTransaction(Long transactionId, CreditLedger.Kind kind, CreditSource source,
        CreditUsage usage, long availableDelta, long reservedDelta, long availableAfter,
        long reservedAfter, LocalDateTime createdAt) {
    static CreditTransaction from(CreditLedger row) {
        return new CreditTransaction(row.getId(), row.getKind(), row.getSourceType(), row.getUsage(),
                row.getAvailableDelta(), row.getReservedDelta(), row.getAvailableAfter(),
                row.getReservedAfter(), row.getCreatedAt());
    }
}
