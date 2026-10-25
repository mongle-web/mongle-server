package com.mongle.backend.domain.credit;
/** 내부 지급 결과. 공개 API로 노출하지 않는다. */
public record CreditGrantReceipt(Long grantId, long amount, CreditSource source) {
    static CreditGrantReceipt from(CreditGrant g) { return new CreditGrantReceipt(g.getId(), g.getAmount(), g.getSourceType()); }
}
