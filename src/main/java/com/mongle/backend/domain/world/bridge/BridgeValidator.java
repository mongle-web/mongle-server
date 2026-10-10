package com.mongle.backend.domain.world.bridge;

import com.mongle.backend.global.error.BusinessException;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
public class BridgeValidator {
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    public String parse(String raw) {
        try {
            if (raw == null || raw.length() > 8000) throw new IllegalArgumentException();
            var root = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
            if (root == null || !root.isObject() || root.size() != 1 || !root.has("content")
                    || !root.get("content").isString()) throw new IllegalArgumentException();
            String text = root.get("content").asString().strip();
            if (text.codePointCount(0, text.length()) > 500
                    || text.codePoints().noneMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c))
                    || text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
                throw new IllegalArgumentException();
            }
            return text;
        } catch (RuntimeException ex) {
            // AI 출력과 파서 오류 본문은 응답·로그에 노출하지 않는다.
            throw new BusinessException(BridgeErrorCode.INVALID_OUTPUT);
        }
    }
}
