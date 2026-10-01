package com.mongle.backend.domain.dream.entity;

import com.mongle.backend.domain.dream.exception.DreamErrorCode;
import com.mongle.backend.global.error.BusinessException;
import java.util.Collection;
import java.util.HashSet;

public final class DreamPolicy {
    private DreamPolicy() { }

    public static void text(String value, boolean allowBlank) {
        // 이모지도 한 글자로 계산한다. 원문의 줄바꿈과 앞뒤 공백은 그대로 저장한다.
        if (value == null || value.codePointCount(0, value.length()) > 500
                || (!allowBlank && value.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)))) {
            throw new BusinessException(DreamErrorCode.INVALID_TEXT);
        }
    }

    public static void emotions(Collection<DreamEmotion> values) {
        if (values == null || values.isEmpty() || values.size() > 3 || values.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(values).size() != values.size()) {
            throw new BusinessException(DreamErrorCode.INVALID_EMOTIONS);
        }
    }

    public static void title(String value) {
        if (value != null && (value.isBlank() || value.codePointCount(0, value.length()) > 100)) {
            throw new BusinessException(DreamErrorCode.INVALID_TITLE);
        }
    }
}
