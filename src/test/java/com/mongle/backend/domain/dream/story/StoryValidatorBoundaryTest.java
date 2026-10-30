package com.mongle.backend.domain.dream.story;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.dream.analysis.StructureResult;
import com.mongle.backend.global.error.BusinessException;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

import java.util.*;
import java.util.stream.IntStream;

class StoryValidatorBoundaryTest {
    final StoryValidator validator = new StoryValidator();
    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void preservesConditionalSceneMappingAndBridgeRulesWithoutProviderConditions() {
        String valid = StoryValidatorTest.VALID;
        var scenes = StoryValidatorTest.SCENES;
        for (String invalid :
                List.of(
                        valid.replace("\"sceneSequence\":1", "\"sceneSequence\":null"),
                        valid.replace("\"sceneSequence\":1", "\"sceneSequence\":\"1\""),
                        valid.replace("\"sceneSequence\":1", "\"sceneSequence\":true"),
                        valid.replace("\"sceneSequence\":1", "\"sceneSequence\":0"),
                        valid.replace("\"sceneSequence\":2", "\"sceneSequence\":11"),
                        valid.replace("\"sceneSequence\":1,", ""),
                        valid.replace("\"sceneSequence\":null", "\"sceneSequence\":2"),
                        valid.replace("\"sceneSequence\":null", "\"sceneSequence\":\"null\""),
                        valid.replace("\"sequence\":3", "\"sequence\":20")))
            invalid(invalid, scenes);
        var three = scenes(3);
        var sections = json.createArrayNode();
        add(sections, "SCENE", 1, "첫 장면");
        add(sections, "AI_BRIDGE", null, "연결");
        add(sections, "AI_BRIDGE", null, "연결");
        add(sections, "SCENE", 2, "두 번째");
        add(sections, "SCENE", 3, "세 번째");
        invalid(story(sections), three);
    }

    @Test
    void rejectsIntegersOutsideRangeBeforeAnyLossyConversion() {
        for (String wide : List.of("18446744073709551617", "-18446744073709551615")) {
            invalid(
                    StoryValidatorTest.VALID.replace("\"sequence\":1", "\"sequence\":" + wide),
                    StoryValidatorTest.SCENES);
            invalid(
                    StoryValidatorTest.VALID.replace(
                            "\"sceneSequence\":1", "\"sceneSequence\":" + wide),
                    StoryValidatorTest.SCENES);
        }
    }

    @Test
    void acceptsTenScenesAndNineteenParagraphsButRejectsOmissionsAndExtraParagraphs() {
        var input = scenes(10);
        var sections = json.createArrayNode();
        for (int i = 1; i <= 10; i++) {
            add(sections, "SCENE", i, "장면");
            if (i < 10) add(sections, "AI_BRIDGE", null, "전환");
        }
        var result = validator.parse(story(sections), input);
        assertThat(result.sections()).hasSize(19);
        assertThat(
                        result.sections().stream()
                                .filter(s -> s.kind() == StoryResult.Kind.SCENE)
                                .map(StoryResult.Section::sceneSequence)
                                .toList())
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        add(sections, "AI_BRIDGE", null, "마지막 연결 금지");
        invalid(story(sections), input);
        invalid(story(json.createArrayNode()), input);
        invalid(StoryValidatorTest.VALID, scenes(11));
    }

    @Test
    void keepsTheSixThousandCodePointTotalAndPerParagraphLimits() {
        var sections = json.createArrayNode();
        for (int i = 1; i <= 4; i++) add(sections, "SCENE", i, "🌙".repeat(1500));
        assertThat(validator.parse(story(sections), scenes(4)).sections()).hasSize(4);
        var excess = json.createArrayNode();
        add(excess, "SCENE", 1, "🌙".repeat(1500));
        add(excess, "AI_BRIDGE", null, "가");
        for (int i = 2; i <= 4; i++) add(excess, "SCENE", i, "🌙".repeat(1500));
        invalid(story(excess), scenes(4));
        var longOne = json.createArrayNode();
        add(longOne, "SCENE", 1, "🌙".repeat(1501));
        invalid(story(longOne), scenes(1));
    }

    private List<StructureResult.Scene> scenes(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> new StructureResult.Scene(i, "장면", i > 1, List.<String>of()))
                .toList();
    }

    private void add(ArrayNode sections, String kind, Integer scene, String content) {
        var node =
                sections.addObject()
                        .put("sequence", sections.size())
                        .put("kind", kind)
                        .put("content", content);
        if (scene == null) node.putNull("sceneSequence");
        else node.put("sceneSequence", scene);
    }

    private String story(ArrayNode sections) {
        return json.writeValueAsString(json.createObjectNode().set("sections", sections));
    }

    private void invalid(String raw, List<StructureResult.Scene> scenes) {
        assertThatThrownBy(() -> validator.parse(raw, scenes))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        failure -> {
                            assertThat(failure.getErrorCode())
                                    .isEqualTo(StoryErrorCode.INVALID_OUTPUT);
                            assertThat(failure.getCause()).isNull();
                        });
    }
}
