package com.mongle.backend.domain.dream.analysis;

import com.mongle.backend.domain.dream.entity.DreamEntityType;
import com.mongle.backend.global.error.BusinessException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import com.mongle.backend.domain.dream.entity.DreamElementNames;
import java.util.*;

@Component
public class StructureValidator {
    private final JsonMapper json =
            JsonMapper.builder()
                    .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build();

    public StructureResult parse(String raw) {
        try {
            check(raw != null && raw.length() <= 32000);
            // readTree만 사용하면 두 번째 루트 JSON이 무시될 수 있으므로 trailing token을 검사한다.
            JsonNode root =
                    json.reader()
                            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .readTree(raw);
            fields(root, Set.of("elements", "scenes"));
            var elements = root.get("elements");
            var scenes = root.get("scenes");
            check(
                    elements.isArray()
                            && elements.size() <= 30
                            && scenes.isArray()
                            && scenes.size() >= 1
                            && scenes.size() <= 10);
            var keys = new HashSet<String>();
            var names = new HashSet<String>();
            var used = new HashSet<String>();
            var parsedElements = new ArrayList<StructureResult.Element>();
            for (var e : elements) {
                fields(e, Set.of("key", "type", "name", "description"));
                String key = text(e.get("key"), 40);
                check(key.matches("[a-zA-Z][a-zA-Z0-9_-]{0,39}") && keys.add(key));
                var type = DreamEntityType.valueOf(text(e.get("type"), 30));
                String name = text(e.get("name"), 100);
                String description = text(e.get("description"), 500);
                check(names.add(type + ":" + normalize(name)));
                parsedElements.add(new StructureResult.Element(key, type, name, description));
            }
            var parsedScenes = new ArrayList<StructureResult.Scene>();
            int sequence = 0;
            for (var s : scenes) {
                fields(s, Set.of("sequence", "content", "disconnectedFromPrevious", "elementKeys"));
                check(
                        s.get("sequence").isIntegralNumber()
                                && s.get("sequence").asLong() == ++sequence);
                check(s.get("disconnectedFromPrevious").isBoolean());
                boolean disconnected = s.get("disconnectedFromPrevious").asBoolean();
                check(sequence != 1 || !disconnected);
                String content = text(s.get("content"), 1000);
                var refs = s.get("elementKeys");
                check(refs.isArray() && refs.size() <= 30);
                var list = new ArrayList<String>();
                var unique = new HashSet<String>();
                for (var ref : refs) {
                    String key = text(ref, 40);
                    check(keys.contains(key) && unique.add(key));
                    list.add(key);
                    used.add(key);
                }
                parsedScenes.add(
                        new StructureResult.Scene(
                                sequence, content, disconnected, List.copyOf(list)));
            }
            check(used.equals(keys));
            return new StructureResult(List.copyOf(parsedElements), List.copyOf(parsedScenes));
        } catch (RuntimeException ex) {
            // 파서 예외는 생성 응답 일부를 포함할 수 있다. 원문 예외를 로그/응답에 연결하지 않는다.
            throw new BusinessException(AnalysisErrorCode.INVALID_OUTPUT);
        }
    }

    private static void fields(JsonNode node, Set<String> expected) {
        check(node != null && node.isObject());
        var actual = new HashSet<String>();
        node.properties().forEach(e -> actual.add(e.getKey()));
        check(actual.equals(expected));
    }

    private static String text(JsonNode n, int max) {
        check(n != null && n.isString());
        String value = n.asString().strip();
        check(
                value.codePoints()
                                .anyMatch(
                                        c ->
                                                !Character.isWhitespace(c)
                                                        && !Character.isSpaceChar(c))
                        && value.codePointCount(0, value.length()) <= max);
        return value;
    }

    public static String normalize(String name) {
        return DreamElementNames.normalize(name);
    }

    private static void check(boolean valid) {
        if (!valid) throw new IllegalArgumentException();
    }
}
