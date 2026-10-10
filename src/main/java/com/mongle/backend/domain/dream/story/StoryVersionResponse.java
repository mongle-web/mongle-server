package com.mongle.backend.domain.dream.story;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record StoryVersionResponse(
        Long versionId,
        Long storyId,
        Long analysisId,
        Long dreamId,
        LocalDate dreamedAt,
        long sourceRevision,
        String promptVersion,
        LocalDateTime storedAt,
        boolean sourceDeleted,
        boolean sourceChanged,
        boolean imported,
        List<StoryResult.Section> sections) {
    public StoryVersionResponse {
        sections = List.copyOf(sections);
    }
}
