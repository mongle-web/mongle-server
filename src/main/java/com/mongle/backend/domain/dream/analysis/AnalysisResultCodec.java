package com.mongle.backend.domain.dream.analysis;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 외부 AI 응답은 StructureValidator로 검증하고, 검증된 내부 결과만 직렬화한다. */
@Component
public class AnalysisResultCodec {
    private final JsonMapper json = JsonMapper.builder().build();

    public String encode(StructureResult result) {
        return json.writeValueAsString(result);
    }

    public StructureResult decode(String encoded) {
        return json.readValue(encoded, StructureResult.class);
    }
}
