package com.mongle.backend.domain.ai.gateway;

import com.mongle.backend.domain.ai.dto.request.AiGenerationRequest;
import com.mongle.backend.domain.ai.dto.response.AiGenerationResult;
import com.mongle.backend.domain.ai.error.AiGatewayException;

/**
 * 도메인 서비스가 제공자에 관계없이 텍스트 생성을 요청하는 동기식 Gateway.
 *
 * <p>호출부는 프롬프트와 출력 스키마를 완성해서 전달한다. 구현체는 외부 응답을
 * {@link AiGenerationResult}로 변환하고 외부 호출 실패를 {@link AiGatewayException}으로
 * 전달한다. 정상 반환만으로 도메인 분석의 성공이 확정되지는 않으며, 결과의 역직렬화,
 * 업무 검증과 엔티티 저장은 호출부에서 수행한다.</p>
 *
 * <p>현재 계약은 비스트리밍 텍스트/JSON 생성만 다룬다. 도구 실행, 이미지 입력,
 * 스트리밍 구독은 별도 계약이 필요하다. 외부 응답을 기다리는 동안 DB 트랜잭션을
 * 열어두지 않도록 도메인 서비스의 트랜잭션 경계를 구성한다.</p>
 */
public interface AiGateway {

    /**
     * 한 번의 논리적인 생성 요청을 처리한다.
     *
     * @param request 도메인이 작성한 메시지와 호출 맥락. {@code null}을 허용하지 않는다.
     * @return 생성 내용 및 메타데이터. 제공자가 생략한 값은 DTO의 누락 규칙을 따른다.
     * @throws AiGatewayException 외부 호출 실패 또는 사용할 수 없는 제공자 응답
     */
    AiGenerationResult generate(AiGenerationRequest request);
}
