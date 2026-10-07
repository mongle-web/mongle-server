package com.mongle.backend.domain.dream.story;

import com.mongle.backend.domain.dream.analysis.StructureResult;
import com.mongle.backend.global.error.BusinessException;

import org.springframework.stereotype.Component;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class StoryValidator {
    private final JsonMapper json =
            JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    public StoryResult parse(String raw, List<StructureResult.Scene> scenes) {
        try {
            check(raw != null && raw.length() <= 32000 && !scenes.isEmpty() && scenes.size() <= 10);

            JsonNode root =
                    json.reader()
                            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .readTree(raw);
            fields(root, Set.of("sections"));

            var sections = root.get("sections");
            check(sections.isArray() && sections.size() >= scenes.size());
            check(sections.size() <= scenes.size() * 2 - 1);

            var result = new ArrayList<StoryResult.Section>();
            int sceneIndex = 0;
            boolean previousBridge = false;
            int total = 0;

            for (var node : sections) {
                fields(node, Set.of("sequence", "kind", "sceneSequence", "content"));
                check(node.get("sequence").isIntegralNumber());
                check(node.get("sequence").asLong() == result.size() + 1L);

                var kind = StoryResult.Kind.valueOf(text(node.get("kind"), 20));
                String content = text(node.get("content"), 1500);
                total += content.codePointCount(0, content.length());
                check(total <= 6000);

                Integer sceneSequence = null;

                if (kind == StoryResult.Kind.SCENE) {
                    check(sceneIndex < scenes.size());
                    check(node.get("sceneSequence").isIntegralNumber());
                    sceneSequence = scenes.get(sceneIndex++).sequence();
                    check(node.get("sceneSequence").asLong() == sceneSequence);
                    previousBridge = false;
                } else {
                    // 연결부는 두 장면 사이에 하나만 허용한다. 첫/마지막 연결부는 거절한다.
                    check(node.get("sceneSequence").isNull());
                    check(sceneIndex > 0 && sceneIndex < scenes.size() && !previousBridge);
                    previousBridge = true;
                }

                result.add(
                        new StoryResult.Section(result.size() + 1, kind, sceneSequence, content));
            }

            check(sceneIndex == scenes.size() && !previousBridge);

            return new StoryResult(result);
        } catch (RuntimeException ex) {
            // 원문 또는 AI 출력이 포함될 수 있는 파서 예외를 응답/로그에 연결하지 않는다.
            throw new BusinessException(StoryErrorCode.INVALID_OUTPUT);
        }
    }

    public String encode(StoryResult result) {
        return json.writeValueAsString(result);
    }

    public StoryResult decode(String raw) {
        return json.readValue(raw, StoryResult.class);
    }

    private static void fields(JsonNode node, Set<String> expected) {
        check(node != null && node.isObject());
        var actual = new HashSet<String>();
        node.properties().forEach(property -> actual.add(property.getKey()));
        check(actual.equals(expected));
    }

    private static String text(JsonNode node, int max) {
        check(node != null && node.isString());
        String value = node.asString().strip();
        check(value.codePointCount(0, value.length()) <= max);
        check(
                value.codePoints()
                        .anyMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c)));

        return value;
    }

    private static void check(boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException();
        }
    }
}
