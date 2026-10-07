package com.mongle.backend.domain.dream.story;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.dream.analysis.StructureResult;
import com.mongle.backend.global.error.BusinessException;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

class StoryValidatorTest {
    static final String VALID =
            """
            {"sections":[
              {"sequence":1,"kind":"SCENE","sceneSequence":1,"content":"바다 위를 날았다."},
              {"sequence":2,"kind":"AI_BRIDGE","sceneSequence":null,"content":"어느 순간 풍경이 바뀌었다."},
              {"sequence":3,"kind":"SCENE","sceneSequence":2,"content":"숲길을 걸었다."}
            ]}
            """;
    static final List<StructureResult.Scene> SCENES =
            List.of(
                    new StructureResult.Scene(1, "바다 위를 날았다", false, List.of()),
                    new StructureResult.Scene(2, "숲길을 걸었다", true, List.of()));

    private final StoryValidator validator = new StoryValidator();

    @Test
    void keepsSceneOrderAndSeparatesOptionalAiBridges() {
        var result = validator.parse(VALID, SCENES);

        assertThat(result.sections()).hasSize(3);
        assertThat(result.sections().get(1).kind()).isEqualTo(StoryResult.Kind.AI_BRIDGE);
        assertThat(result.sections().get(1).sceneSequence()).isNull();
        assertThat(validator.decode(validator.encode(result))).isEqualTo(result);

        var noBridge =
                VALID.replace(
                                "{\"sequence\":2,\"kind\":\"AI_BRIDGE\",\"sceneSequence\":null,\"content\":\"어느"
                                    + " 순간 풍경이 바뀌었다.\"},",
                                "")
                        .replace("\"sequence\":3", "\"sequence\":2");
        assertThat(validator.parse(noBridge, SCENES).sections()).hasSize(2);
    }

    @Test
    void rejectsMalformedAndAmbiguousOutputs() {
        List<String> invalid =
                List.of(
                        "{}",
                        "null",
                        "[]",
                        VALID + " {}",
                        "```json\n" + VALID + "\n```",
                        VALID.replace("\"sections\":", "\"extra\":1,\"sections\":"),
                        VALID.replace("\"sequence\":1", "\"sequence\":1,\"sequence\":1"),
                        VALID.replace("\"sequence\":3", "\"sequence\":4"),
                        VALID.replace("\"sequence\":1", "\"sequence\":1.0"),
                        VALID.replace("\"sceneSequence\":2", "\"sceneSequence\":1"),
                        VALID.replace("\"sceneSequence\":2", "\"sceneSequence\":3"),
                        VALID.replace("\"sceneSequence\":2", "\"sceneSequence\":2.0"),
                        VALID.replace("\"sceneSequence\":null", "\"sceneSequence\":1"),
                        VALID.replace("바다 위를 날았다.", "\u00a0\u2003"),
                        VALID.replace("바다 위를 날았다.", "가".repeat(1501)),
                        VALID.replace("\"kind\":\"SCENE\"", "\"kind\":\"UNKNOWN\""),
                        "{\"sections\":[]}",
                        VALID.replace("\"sceneSequence\":1", "\"sceneSequence\":null"));

        for (String raw : invalid) {
            assertInvalid(raw, SCENES);
        }

        assertInvalid(null, SCENES);
        assertInvalid("x".repeat(32001), SCENES);
        assertInvalid(VALID, List.of());
    }

    @Test
    void rejectsMissingScenesAndBridgesAtTheEdgesOrInARow() {
        String first =
                "{\"sequence\":1,\"kind\":\"AI_BRIDGE\",\"sceneSequence\":null,\"content\":\"연결\"}";
        String scene = "{\"sequence\":1,\"kind\":\"SCENE\",\"sceneSequence\":1,\"content\":\"장면\"}";

        assertInvalid("{\"sections\":[" + first + "]}", SCENES);
        assertInvalid("{\"sections\":[" + scene + "]}", SCENES);
        assertInvalid(
                "{\"sections\":[" + scene + "," + first.replace(":1,", ":2,") + "]}",
                SCENES.subList(0, 1));

        String consecutive =
                VALID.replace(
                        "\"sequence\":3,\"kind\":\"SCENE\",\"sceneSequence\":2",
                        "\"sequence\":3,\"kind\":\"AI_BRIDGE\",\"sceneSequence\":null");
        assertInvalid(consecutive, SCENES);
    }

    @Test
    void countsUnicodeCodePointsAndLimitsTotalStoryLength() {
        String one =
                "{\"sections\":[{\"sequence\":1,\"kind\":\"SCENE\",\"sceneSequence\":1,\"content\":\"%s\"}]}";

        assertThat(
                        validator
                                .parse(one.formatted("😀".repeat(1500)), SCENES.subList(0, 1))
                                .sections())
                .hasSize(1);
        assertInvalid(one.formatted("😀".repeat(1501)), SCENES.subList(0, 1));

        var five =
                IntStream.rangeClosed(1, 5)
                        .mapToObj(i -> new StructureResult.Scene(i, "장면", i > 1, List.<String>of()))
                        .toList();
        String tooLong =
                IntStream.rangeClosed(1, 5)
                        .mapToObj(
                                i ->
                                        "{\"sequence\":%d,\"kind\":\"SCENE\",\"sceneSequence\":%d,\"content\":\"%s\"}"
                                                .formatted(i, i, "가".repeat(1300)))
                        .collect(java.util.stream.Collectors.joining(",", "{\"sections\":[", "]}"));

        assertInvalid(tooLong, five);
    }

    @Test
    void inputDoesNotExposeDreamContentsInToString() {
        var input =
                new StoryGenerator.Input(
                        1L, 2L, 3L, "attempt", 4L, "개인 원문", java.util.Set.of(), SCENES, List.of());

        assertThat(input.toString()).contains("storyId=2").doesNotContain("개인 원문", "바다", "숲");
        assertThat(StoryPrompt.system()).contains("AI_BRIDGE");
        assertThat(StoryPrompt.schema()).contains("sceneSequence");
    }

    private void assertInvalid(String raw, List<StructureResult.Scene> scenes) {
        assertThatThrownBy(() -> validator.parse(raw, scenes))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(StoryErrorCode.INVALID_OUTPUT));
    }
}
