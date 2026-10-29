package com.mongle.backend.global.common;

import com.mongle.backend.global.error.BusinessException;
import com.mongle.backend.global.error.ErrorCode;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/** 목록 검색의 공백·길이·대소문자와 LIKE 리터럴 처리를 공유한다. */
public final class SearchQuery {
    private SearchQuery() {}

    public static String normalize(@Nullable String value, ErrorCode invalidQuery) {
        if (value == null) return "";
        int start = 0, end = value.length();
        while (start < end && blank(value.codePointAt(start)))
            start += Character.charCount(value.codePointAt(start));
        while (start < end && blank(value.codePointBefore(end)))
            end -= Character.charCount(value.codePointBefore(end));
        String stripped = value.substring(start, end);
        if (stripped.codePointCount(0, stripped.length()) > 50)
            throw new BusinessException(invalidQuery);
        return stripped.toLowerCase(Locale.ROOT);
    }

    /** SQL에는 문자열을 삽입하지 않고 이 값을 바인딩하며 ESCAPE '!'를 함께 사용한다. */
    public static @Nullable String containsPattern(String normalized) {
        if (normalized.isEmpty()) return null;
        return "%" + normalized.replace("!", "!!").replace("%", "!%")
                .replace("_", "!_") + "%";
    }

    private static boolean blank(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
