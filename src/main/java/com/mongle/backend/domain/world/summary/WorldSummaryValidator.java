package com.mongle.backend.domain.world.summary;

import com.mongle.backend.global.error.BusinessException;

import org.springframework.stereotype.Component;

import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

@Component
public class WorldSummaryValidator {
    private final JsonMapper json =
            JsonMapper.builder()
                    .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build();

    public String title(String raw) {
        try {
            if (raw == null || raw.length() > 2000) throw new IllegalArgumentException();
            JsonNode root =
                    json.reader()
                            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .readTree(raw);
            if (!root.isObject()
                    || root.size() != 1
                    || !root.has("generatedTitle")
                    || !root.get("generatedTitle").isString()) throw new IllegalArgumentException();
            String title =
                    root.get("generatedTitle")
                            .asString()
                            .replaceAll("^[\\p{Z}\\s]+|[\\p{Z}\\s]+$", "");
            if (title.isEmpty()
                    || title.codePointCount(0, title.length()) > 80
                    || title.codePoints()
                            .anyMatch(
                                    c ->
                                            Character.isISOControl(c)
                                                    || Character.getType(c) == Character.FORMAT
                                                    || c == '<'
                                                    || c == '>'))
                throw new IllegalArgumentException();
            return title;
        } catch (RuntimeException ex) {
            throw new BusinessException(WorldSummaryErrorCode.INVALID_OUTPUT);
        }
    }
}
