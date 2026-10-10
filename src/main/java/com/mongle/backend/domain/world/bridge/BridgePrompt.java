package com.mongle.backend.domain.world.bridge;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class BridgePrompt {
    public static final String VERSION = "world-bridge-v1";

    private BridgePrompt() {}

    public static String system() {
        return resource("world-bridge-prompt.txt");
    }

    public static String schema() {
        return resource("world-bridge-schema.json");
    }

    private static String resource(String name) {
        try (var stream = new ClassPathResource("ai/" + name).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("꿈 연결 계약 리소스를 읽을 수 없습니다.", ex);
        }
    }
}
