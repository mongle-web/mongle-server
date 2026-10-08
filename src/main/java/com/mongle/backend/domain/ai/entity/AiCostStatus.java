package com.mongle.backend.domain.ai.entity;

/** 비용 0과 비용을 계산할 수 없는 상태를 구분한다. 실패한 생성도 사용량이 있으면 과금될 수 있다. */
public enum AiCostStatus {
    CALCULATED,     // 응답 사용량과 등록된 단가로 USD 추정 비용을 계산했다.
    USAGE_MISSING,  // 입력·출력·캐시 입력 중 필요한 수량이 빠져 계산을 보류했다.
    PRICE_MISSING,  // 응답 모델명 또는 해당 모델의 단가가 없어 계산을 보류했다.
    LEGACY         // 기존 기록의 비용을 보존했다. 새 계산 규칙으로 산출한 값은 아니다.
}
