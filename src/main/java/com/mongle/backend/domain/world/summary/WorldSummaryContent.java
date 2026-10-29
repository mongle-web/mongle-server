package com.mongle.backend.domain.world.summary;

import com.mongle.backend.domain.dream.entity.DreamEmotion;

import java.util.*;

public record WorldSummaryContent(
        String generatedTitle,
        List<Keyword> commonKeywords,
        List<Emotion> recurringEmotions,
        int sourceCount,
        boolean limitedEvidence) {
    public WorldSummaryContent {
        commonKeywords = List.copyOf(commonKeywords);
        recurringEmotions = List.copyOf(recurringEmotions);
    }

    public record Keyword(String keyword, long dreamCount) {}

    public record Emotion(DreamEmotion code, String label, long dreamCount) {}
}
