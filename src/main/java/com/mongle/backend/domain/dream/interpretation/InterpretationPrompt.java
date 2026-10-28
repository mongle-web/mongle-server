package com.mongle.backend.domain.dream.interpretation;

/** 응답과 출처 지문의 계약 버전. 실제 생성 프롬프트는 Gateway 어댑터에서 사용한다. */
public final class InterpretationPrompt {
    public static final String VERSION = "recent-interpretation-v1";
    private InterpretationPrompt() {}
}
