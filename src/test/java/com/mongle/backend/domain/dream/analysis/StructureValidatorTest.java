package com.mongle.backend.domain.dream.analysis;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.global.error.BusinessException;

import org.junit.jupiter.api.Test;

class StructureValidatorTest {
    private final StructureValidator validator = new StructureValidator();
    static final String VALID =
            """
{"generatedTitle":"바다 위를 날다","displayKeywords":["바다"],"elements":[{"key":"sea","type":"PLACE","name":"바다","description":"파란 바다"}],
"scenes":[{"sequence":1,"content":"바다 위를 날았다","disconnectedFromPrevious":false,"elementKeys":["sea"]}]}
""";

    @Test
    void acceptsOneSceneAndKeepsDomainReferences() {
        var r = validator.parse(VALID);
        assertThat(r.scenes()).hasSize(1);
        assertThat(r.scenes().getFirst().elementKeys()).containsExactly("sea");
    }

    @Test
    void rejectsUnknownReferences() {
        reject(VALID.replace("[\"sea\"]", "[\"other\"]"));
    }

    @Test
    void rejectsDuplicateReferences() {
        reject(VALID.replace("[\"sea\"]", "[\"sea\",\"sea\"]"));
    }

    @Test
    void rejectsInventedDatabaseIdentifiers() {
        reject(VALID.replace("\"sequence\":1", "\"userId\":123,\"sequence\":1"));
    }

    @Test
    void rejectsSkippedSequenceAndDisconnectedFirstScene() {
        reject(VALID.replace("\"sequence\":1", "\"sequence\":2"));
        reject(VALID.replace(":false", ":true"));
    }

    @Test
    void rejectsBlankContentAndMarkdown() {
        reject(VALID.replace("바다 위를 날았다", "　 "));
        reject("```json\n" + VALID + "```");
    }

    @Test
    void rejectsUnusedElementsAndMultipleJsonRoots() {
        reject(VALID.replace("[\"sea\"]", "[]"));
        reject(VALID + "{}");
    }

    @Test
    void normalizesUnicodeWhitespaceAndCase() {
        assertThat(StructureValidator.normalize("　ＦＯＲＥＳＴ  ")).isEqualTo("forest");
        assertThat(StructureValidator.normalize("어린　 시절\t집")).isEqualTo("어린 시절 집");
    }

    @Test
    void acceptsAndTrimsDisplayMetadataAndPreservesKeywordOrder() {
        var result =
                validator.parse(
                        VALID.replace("바다 위를 날다", "　바다 위를 날다　")
                                .replace(
                                        "\"displayKeywords\":[\"바다\"]",
                                        "\"displayKeywords\":[\"　바다　\",\"비행\"]"));
        assertThat(result.generatedTitle()).isEqualTo("바다 위를 날다");
        assertThat(result.displayKeywords()).containsExactly("바다", "비행");
        assertThatThrownBy(() -> result.displayKeywords().add("다른 태그"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void enforcesTitleCodePointLengthAndSingleLineNonblankText() {
        assertThat(validator.parse(VALID.replace("바다 위를 날다", "🌊".repeat(20))).generatedTitle())
                .isEqualTo("🌊".repeat(20));
        reject(VALID.replace("바다 위를 날다", "🌊".repeat(21)));
        reject(VALID.replace("바다 위를 날다", "　　"));
        reject(VALID.replace("바다 위를 날다", "꿈\\n제목"));
        reject(VALID.replace("바다 위를 날다", "꿈\\u2028제목"));
        reject(VALID.replace("\"바다 위를 날다\"", "null"));
    }

    @Test
    void enforcesOneToFiveKeywordsAndNormalizedUniqueness() {
        String five = "\"displayKeywords\":[\"바다\",\"비행\",\"숲\",\"빛\",\"밤\"]";
        assertThat(
                        validator
                                .parse(VALID.replace("\"displayKeywords\":[\"바다\"]", five))
                                .displayKeywords())
                .hasSize(5);
        for (String keywords :
                java.util.List.of(
                        "[]",
                        "null",
                        "\"바다\"",
                        "[null]",
                        "[1]",
                        "[\"　\"]",
                        "[\"바다\",\"　바다　\"]",
                        "[\"ＦＯＲＥＳＴ\",\"forest\"]",
                        "[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]",
                        "[\"꿈\\n태그\"]",
                        "[\"" + "🌊".repeat(21) + "\"]")) {
            reject(VALID.replace("[\"바다\"]", keywords));
        }
        assertThat(
                        validator
                                .parse(VALID.replace("[\"바다\"]", "[\"" + "🌊".repeat(20) + "\"]"))
                                .displayKeywords())
                .hasSize(1);
    }

    @Test
    void rejectsLegacyOutputMissingMetadataUnknownFieldsAndDuplicateJsonKeys() {
        reject(VALID.replace("\"generatedTitle\":\"바다 위를 날다\",", ""));
        reject(VALID.replace("\"displayKeywords\":[\"바다\"],", ""));
        reject(VALID.replace("\"generatedTitle\":", "\"title\":\"다른 제목\",\"generatedTitle\":"));
        reject(
                VALID.replace(
                        "\"generatedTitle\":", "\"generatedTitle\":\"중복\",\"generatedTitle\":"));
    }

    private void reject(String output) {
        assertThatThrownBy(() -> validator.parse(output))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(AnalysisErrorCode.INVALID_OUTPUT));
    }
}
