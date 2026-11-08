package com.mongle.backend.domain.user.withdrawal;

/** 회원 행 잠금 안에서 구매별 실제 접수 내역과 미완료 접수 여부를 확인한다. */
public interface WithdrawalRefundPolicy {
    boolean coversRemainingPurchases(Long userId);
    boolean hasOpenRefunds(Long userId);
}
