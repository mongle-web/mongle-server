package com.mongle.backend.domain.world.bridge;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.global.error.BusinessException;

import org.junit.jupiter.api.Test;

import java.util.List;

class BridgeValidatorTest {
    private final BridgeValidator validator = new BridgeValidator();

    @Test
    void acceptsShortTextAndCountsUnicodeCodePoints() {
        assertThat(validator.parse("{\"content\":\"  바다를 지나 숲으로 향했다.  \"}"))
                .isEqualTo("바다를 지나 숲으로 향했다.");
        assertThat(validator.parse("{\"content\":\"" + "🌙".repeat(500) + "\"}"))
                .isEqualTo("🌙".repeat(500));
    }

    @Test
    void rejectsMalformedExtraDuplicateEmptyAndOversizedOutputsWithoutExposingText() {
        for (String raw :
                List.of(
                        "null",
                        "{}",
                        "[]",
                        "비밀 원문",
                        "{\"content\":42}",
                        "{\"content\":\"\"}",
                        "{\"content\":\"\\u00a0\\u2007\"}",
                        "{\"content\":\"비밀\",\"extra\":1}",
                        "{\"content\":\"a\",\"content\":\"b\"}",
                        "{\"content\":\"a\"} {}",
                        "{\"content\":\"\\u0000\"}",
                        "{\"content\":\"" + "가".repeat(501) + "\"}",
                        " ".repeat(8001))) {
            assertThatThrownBy(() -> validator.parse(raw))
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            error -> {
                                assertThat(error.getErrorCode())
                                        .isEqualTo(BridgeErrorCode.INVALID_OUTPUT);
                                assertThat(error.getCause()).isNull();
                                assertThat(error.getMessage()).doesNotContain("비밀");
                            });
        }
        assertThatThrownBy(() -> validator.parse(null)).isInstanceOf(BusinessException.class);
    }
}
