package com.mongle.backend.domain.dream.analysis;

import com.mongle.backend.domain.dream.entity.DreamEntityType;
import java.util.List;

public record StructureResult(List<Element> elements, List<Scene> scenes) {
    public record Element(String key, DreamEntityType type, String name, String description) {}

    public record Scene(
            int sequence,
            String content,
            boolean disconnectedFromPrevious,
            List<String> elementKeys) {}
}
