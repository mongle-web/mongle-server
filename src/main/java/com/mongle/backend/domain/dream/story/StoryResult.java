package com.mongle.backend.domain.dream.story;

import java.util.List;

public record StoryResult(List<Section> sections) {
    public StoryResult {
        sections = List.copyOf(sections);
    }

    public enum Kind {
        SCENE,
        AI_BRIDGE,
    }

    public record Section(int sequence, Kind kind, Integer sceneSequence, String content) {}
}
