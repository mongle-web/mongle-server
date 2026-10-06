package com.mongle.backend.domain.dream.analysis;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class StructurePrompt {
    public static final String VERSION = "scene-v2-display";

    private StructurePrompt() {}

    public static String system() {
        return resource("structure-prompt.txt");
    }

    public static String schema() {
        return resource("structure-schema.json");
    }

    private static String resource(String name) {
        try (var stream = new ClassPathResource("ai/" + name).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("구조화 계약 리소스를 읽을 수 없습니다.", ex);
        }
    }
}
