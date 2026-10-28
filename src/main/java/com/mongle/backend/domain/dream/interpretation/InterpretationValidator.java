package com.mongle.backend.domain.dream.interpretation;

import com.mongle.backend.global.error.BusinessException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

@Component
public class InterpretationValidator {
    private final JsonMapper json = JsonMapper.builder()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    public String parse(String raw) {
        try {
            if (raw == null || raw.length() > 16000) throw new IllegalArgumentException();
            JsonNode root = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
            if (root == null || !root.isObject() || root.size() != 1 || !root.has("interpretation")
                    || !root.get("interpretation").isString()) throw new IllegalArgumentException();
            String body = root.get("interpretation").asString().strip();
            if (body.codePoints().noneMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c))
                    || body.codePointCount(0, body.length()) > 2000
                    || body.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t'))
                throw new IllegalArgumentException();
            return body;
        } catch (RuntimeException ex) {
            // 파서 예외의 원문·응답 조각을 외부 로그/오류에 연결하지 않는다.
            throw new BusinessException(InterpretationErrorCode.INVALID_OUTPUT);
        }
    }
}
