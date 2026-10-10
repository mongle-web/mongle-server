package com.mongle.backend.domain.dream.story;

import java.time.LocalDateTime;
import java.util.List;

public record StoryVersionPage(List<Item> items, Long nextCursor) {
    public StoryVersionPage {
        items = List.copyOf(items);
    }

    public record Item(
            Long versionId,
            long sourceRevision,
            String promptVersion,
            LocalDateTime storedAt,
            boolean sourceChanged,
            boolean imported) {}
}
