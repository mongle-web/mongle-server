package com.mongle.backend.domain.ai.dto.request;

import tools.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * 도메인이 제공하는 JSON Schema 출력 옵션.
 *
 * <p>Gateway 공통 계약은 스키마 본문을 전달할 수 있는지만 확인한다. 필드 정의,
 * 스키마 문법 전체의 유효성, 제공자의 지원 범위 및 생성 결과의 업무 검증을 이 DTO가
 * 보장하지는 않는다. 그 검증은 도메인과 실제 제공자 어댑터에서 수행한다.</p>
 *
 * @param name 어댑터가 출력 형식에 전달할 비어 있지 않은 스키마 이름
 * @param schema JSON 객체 형태의 스키마 본문. 호출자가 준 트리를 복사하여 보관한다.
 * @param strict 제공자에 스키마 준수를 요청할지 여부. 업무 검증을 대신하는 옵션은 아니다.
 */
public record AiJsonSchema(String name, JsonNode schema, boolean strict) {

    public AiJsonSchema {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(schema, "schema must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (!schema.isObject()) {
            throw new IllegalArgumentException("schema must be a JSON object");
        }
        // JsonNode는 내부 객체/배열이 가변이다. 생성 이후 원본을 수정해도 요청이 바뀌지 않게 복사한다.
        schema = schema.deepCopy();
    }

    /** 반환한 트리를 어댑터가 수정해도 저장된 스키마에 영향을 주지 않도록 복사본을 반환한다. */
    @Override
    public JsonNode schema() {
        return schema.deepCopy();
    }
}
