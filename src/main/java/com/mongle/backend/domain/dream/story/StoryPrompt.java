package com.mongle.backend.domain.dream.story;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class StoryPrompt {

    public static final String VERSION = "story-v1";

    private StoryPrompt() {}

    public static String system() {
        return resource("story-prompt.txt");
    }

    public static String schema() {
        return resource("story-schema.json");
    }

    private static String resource(String name) {
        try (var stream = new ClassPathResource("ai/" + name).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("서사화 계약 리소스를 읽을 수 없습니다.", ex);
        }
    }
}
