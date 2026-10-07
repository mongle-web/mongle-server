package com.mongle.backend.domain.dream.analysis;

import com.mongle.backend.global.error.BusinessException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StructureValidatorTest {
    private final StructureValidator validator = new StructureValidator();
    static final String VALID =
            """
 {"elements":[{"key":"sea","type":"PLACE","name":"바다","description":"파란 바다"}],
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

    private void reject(String output) {
        assertThatThrownBy(() -> validator.parse(output))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(AnalysisErrorCode.INVALID_OUTPUT));
    }
}
