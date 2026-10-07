package com.mongle.backend.domain.ai.dto.request;

import java.util.Objects;

/**
 * AI에 전달할 텍스트 메시지 한 개. 목록의 순서는 그대로 대화 순서가 된다.
 *
 * @param role SYSTEM은 도메인의 지시, USER는 입력, ASSISTANT는 이전 생성 결과
 * @param content 비어 있지 않은 메시지 원문. 줄바꿈과 공백을 임의로 수정하지 않는다.
 */
public record AiMessage(Role role, String content) {

    public AiMessage {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
    }

    /** 현재 텍스트 생성에 필요한 역할만 정의하며 제공자의 역할 문자열 변환은 어댑터가 맡는다. */
    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT
    }
}
