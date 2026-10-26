package com.mongle.backend.domain.credit;
import io.swagger.v3.oas.annotations.media.Schema;
@Schema(description = "현재 남은 크레딧. total은 available+reserved이며 이미 사용 확정된 양은 제외합니다. spendingBlocked=true이면 외부 결제 취소 확인 중으로 예약·새 사용 확정이 중단됩니다.")
public record CreditBalance(long available, long reserved, long total, boolean spendingBlocked) {
    public CreditBalance(long available,long reserved,long total) { this(available,reserved,total,false); }
    static CreditBalance from(CreditAccount a) {
        return new CreditBalance(a.getAvailable(), a.getReserved(), CreditRules.add(a.getAvailable(), a.getReserved()));
    }
    static CreditBalance zero() { return new CreditBalance(0, 0, 0); }
}
