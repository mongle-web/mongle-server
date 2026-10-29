package com.mongle.backend.domain.world.summary;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class WorldSummaryPrompt {
    public static final String VERSION = "world-summary-v1";

    private WorldSummaryPrompt() {}

    public static String system() {
        return read("world-summary-prompt.txt");
    }

    public static String schema() {
        return read("world-summary-schema.json");
    }

    private static String read(String name) {
        try {
            return new ClassPathResource("ai/" + name).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("세계관 결과 계약을 읽을 수 없습니다.");
        }
    }
}
