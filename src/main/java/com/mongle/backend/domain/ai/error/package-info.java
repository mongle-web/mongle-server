/**
 * AI 제공자와 관계없이 사용할 호출 실패 분류와 예외.
 *
 * <p>제공자 어댑터가 외부 실패를 이 계약으로 변환한다. 예외는 기존 공통 BusinessException
 * 처리에 연결되고, 재시도 정보는 실제 정책을 실행하지 않는 힌트로 전달한다.</p>
 */
package com.mongle.backend.domain.ai.error;
