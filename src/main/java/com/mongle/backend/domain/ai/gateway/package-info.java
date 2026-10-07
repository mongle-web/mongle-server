/**
 * 도메인 서비스와 외부 AI 제공자 사이의 공통 호출 계약.
 *
 * <p>도메인은 메시지, 출력 스키마와 업무 검증 규칙을 정의한다. Gateway는 생성 내용과
 * 호출 메타데이터를 반환하며, 꿈 엔티티 저장이나 비용 계산을 수행하지 않는다.
 * 실제 HTTP 연결 구현은 {@code domain.ai.liner} 패키지에서 이 계약을 구현한다.</p>
 *
 * <p>이 패키지에는 호출 인터페이스만 둔다. 내부 요청과 응답은 각각
 * {@code domain.ai.dto.request}, {@code domain.ai.dto.response}에,
 * 실패 계약은 {@code domain.ai.error}에 둔다. DTO는 내부 서비스 간 전달용이며
 * HTTP API나 외부 제공자의 원본 DTO와 구분한다.</p>
 */
package com.mongle.backend.domain.ai.gateway;
