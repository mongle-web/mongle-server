package com.mongle.backend.domain.dream.gateway;

import com.mongle.backend.domain.ai.liner.config.LinerProperties;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/** 호출 시점의 요청 설정만 기록한다. 인증 정보와 응답 모델명은 추정해서 저장하지 않는다. */
@Component
public class DreamGenerationSettings {
    private final LinerProperties properties;
    private final JsonMapper json = JsonMapper.builder().build();

    public DreamGenerationSettings(LinerProperties properties) {
        this.properties = properties;
    }

    public record Request(
            String provider,
            String requestedModel,
            int maxCompletionTokens,
            String reasoningEffort,
            String outputFormat) {}

    public String capture() {
        return json.writeValueAsString(
                new Request(
                        "liner",
                        properties.model(),
                        properties.maxCompletionTokens(),
                        properties.reasoningEffort(),
                        "json_schema"));
    }

    public Request decode(String encoded) {
        return encoded == null ? null : json.readValue(encoded, Request.class);
    }
}
