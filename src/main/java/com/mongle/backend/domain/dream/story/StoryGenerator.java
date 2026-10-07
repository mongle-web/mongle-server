package com.mongle.backend.domain.dream.story;

import com.mongle.backend.domain.dream.analysis.StructureResult;
import com.mongle.backend.domain.dream.entity.DreamEmotion;

import java.util.List;
import java.util.Set;

/** #5 Gateway 계약 확정 후 DREAM_NARRATIVE 어댑터에서 구현할 도메인 포트. */
public interface StoryGenerator {
    default boolean available() {
        return true;
    }

    String generate(Input input);

    record Input(
            Long userId,
            Long storyId,
            Long analysisId,
            String attemptId,
            long sourceRevision,
            String originalText,
            Set<DreamEmotion> emotions,
            List<StructureResult.Scene> scenes,
            List<StructureResult.Element> elements) {
        public Input {
            emotions = Set.copyOf(emotions);
            scenes = List.copyOf(scenes);
            elements = List.copyOf(elements);
        }

        @Override
        public String toString() {
            return "StoryInput[storyId=" + storyId + ",attemptId=" + attemptId + "]";
        }
    }
}
