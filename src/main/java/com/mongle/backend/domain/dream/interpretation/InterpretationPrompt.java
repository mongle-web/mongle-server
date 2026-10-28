package com.mongle.backend.domain.dream.interpretation;

import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 프롬프트와 응답 스키마를 함께 버전 관리한다. */
public final class InterpretationPrompt {
    public static final String VERSION = "recent-interpretation-v1";
    private InterpretationPrompt() {}
    public static String system() { return resource("recent-interpretation-prompt.txt"); }
    public static String schema() { return resource("recent-interpretation-schema.json"); }
    private static String resource(String name) {
        try (var stream = new ClassPathResource("ai/" + name).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) { throw new IllegalStateException("종합 해석 계약을 읽을 수 없습니다.", ex); }
    }
}
