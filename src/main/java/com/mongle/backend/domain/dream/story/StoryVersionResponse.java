package com.mongle.backend.domain.dream.story;

import com.mongle.backend.domain.dream.analysis.StructureResult;
import com.mongle.backend.domain.dream.entity.DreamEmotion;
import com.mongle.backend.domain.dream.gateway.DreamGenerationSettings;

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
        List<DreamEmotion> emotions,
        String analysisPromptVersion,
        StructureResult analysis,
        boolean sourceSnapshotAvailable,
        DreamGenerationSettings.Request analysisSettings,
        DreamGenerationSettings.Request storySettings) {
    public StoryVersionResponse {
        sections = List.copyOf(sections);
        emotions = List.copyOf(emotions);
    }
}
