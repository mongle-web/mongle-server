package com.mongle.backend.global.logging;

import org.jspecify.annotations.Nullable;

/** 외부 모델명·종료 사유 같은 짧은 메타데이터를 한 줄 로그에 안전하게 표시한다. */
public final class LogValues {
    private LogValues() { }

    public static String safe(@Nullable String value) {
        if (value == null) return "unknown";
        // 제어 문자로 별도 로그 행을 만들거나, 비정상 메타데이터가 로그를 과도하게 키우지 않게 한다.
        return value.substring(0, Math.min(value.length(), 256)).replaceAll("[\\p{Cntrl}\\u2028\\u2029]", "_");
    }
}
