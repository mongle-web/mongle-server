package com.mongle.backend.domain.world.composition;

import com.mongle.backend.domain.dream.story.StoryResult;

import java.time.LocalDate;
import java.util.List;

public record WorldEntryPage(
        Long worldId,
        long compositionRevision,
        List<Entry> items,
        boolean hasNext,
        String nextCursor) {
    public record Entry(
            Long dreamId,
            LocalDate dreamedAt,
            Long storyVersionId,
            List<StoryResult.Section> sections,
            Long bridgeBeforeId,
            String bridgeBefore,
            boolean connectionGap,
            boolean sourceChanged,
            boolean newStoryAvailable) {}
}
