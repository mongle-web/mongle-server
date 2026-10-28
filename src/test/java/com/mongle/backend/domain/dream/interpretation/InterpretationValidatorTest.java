package com.mongle.backend.domain.dream.interpretation;

import static org.assertj.core.api.Assertions.*;
import com.mongle.backend.global.error.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.List;

class InterpretationValidatorTest {
    private final InterpretationValidator validator = new InterpretationValidator();
    @Test void acceptsBoundedUnicodePlainText() {
        assertThat(validator.parse("{\"interpretation\":\" 관찰 자료가 적습니다. 바다가 등장합니다. \"}"))
                .isEqualTo("관찰 자료가 적습니다. 바다가 등장합니다.");
        assertThat(validator.parse("{\"interpretation\":\""+"🌙".repeat(2000)+"\"}"))
                .hasSize(4000);
    }
    @Test void malformedOutputsDoNotLeakProviderContent() {
        for (String bad : List.of("provider-private-text", "{}", "[]", "null",
                "{\"interpretation\":7}", "{\"interpretation\":\"　 \"}",
                "{\"interpretation\":\"private\",\"extra\":1}",
                "{\"interpretation\":\"private\",\"interpretation\":\"duplicate\"}",
                "{\"interpretation\":\"private\"} {}",
                "{\"interpretation\":\""+"🌙".repeat(2001)+"\"}",
                "{\"interpretation\":\"\\u0000private\"}")) {
            assertThatThrownBy(() -> validator.parse(bad)).isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo(InterpretationErrorCode.INVALID_OUTPUT);
                assertThat(ex.getCause()).isNull();
                assertThat(ex.getMessage()).doesNotContain("private", "provider");
            });
        }
        assertThatThrownBy(() -> validator.parse(null)).isInstanceOf(BusinessException.class);
    }
}
