/**
 * 도메인 서비스가 AI Gateway에 전달할 내부 요청 데이터.
 *
 * <p>요청에는 호출 맥락, 도메인이 작성한 메시지와 선택적인 출력 스키마를 담는다.
 * 사용자 HTTP 입력 DTO나 LINER API 원본 요청 DTO로 직접 사용하지 않는다.
 * 인증·소유권 검증과 업무별 프롬프트 작성은 도메인 서비스의 책임이다.</p>
 */
package com.mongle.backend.domain.ai.dto.request;
