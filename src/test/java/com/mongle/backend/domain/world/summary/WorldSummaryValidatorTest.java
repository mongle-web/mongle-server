package com.mongle.backend.domain.world.summary;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorldSummaryValidatorTest {
    final WorldSummaryValidator validator = new WorldSummaryValidator();

    @Test
    void acceptsTrimmedTitleAndUnicodeLimit() {
        assertThat(validator.title("{\"generatedTitle\":\"　하늘을 건너 만난 바다　\"}"))
                .isEqualTo("하늘을 건너 만난 바다");
        assertThat(validator.title("{\"generatedTitle\":\"" + "별".repeat(80) + "\"}")).hasSize(80);
        assertThatThrownBy(() -> validator.title("{\"generatedTitle\":\"" + "별".repeat(81) + "\"}"))
                .isInstanceOf(com.mongle.backend.global.error.BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "null",
                "[]",
                "{}",
                "{\"generatedTitle\":1}",
                "{\"generatedTitle\":\" \"}",
                "{\"generatedTitle\":\"a\",\"generatedTitle\":\"b\"}",
                "{\"generatedTitle\":\"a\"} {}",
                "{\"generatedTitle\":\"a\",\"commonKeywords\":[\"invented\"]}",
                "{\"generatedTitle\":\"a\\nb\"}",
                "{\"generatedTitle\":\"<script>\"}",
                "{\"generatedTitle\":\"a\\u200bb\"}"
            })
    void rejectsInvalidAndUntrustedOutputWithoutLeakingIt(String raw) {
        assertThatThrownBy(() -> validator.title(raw))
                .isInstanceOf(com.mongle.backend.global.error.BusinessException.class)
                .hasMessage("세계관 제목 생성 결과를 확인할 수 없습니다.")
                .hasNoCause();
    }
}
