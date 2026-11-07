package com.mongle.backend.domain.user.withdrawal;

/** 후속 환불 기능은 사용자 잠금 안에서 DB의 실제 접수 내역으로 구매별 미사용량을 검증해야 한다.
 * 클라이언트 입력/이메일 발송만으로 접수됐다고 간주하지 않는다. 기본 구현은 유료 탈퇴를 허용하지 않는다. */
public interface WithdrawalRefundPolicy {
    boolean coversRemainingPurchases(Long userId);
    boolean hasOpenRefunds(Long userId);
}
