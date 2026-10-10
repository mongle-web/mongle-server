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
        List<StoryResult.Section> sections,
        String originalText,
        List<com.mongle.backend.domain.dream.entity.DreamEmotion> emotions,
        String analysisPromptVersion,
        com.mongle.backend.domain.dream.analysis.StructureResult analysis,
        boolean sourceSnapshotAvailable) {
    public StoryVersionResponse {
        sections = List.copyOf(sections);
        emotions = List.copyOf(emotions);
    }
}
