package com.mongle.backend.domain.dream.story;

import com.mongle.backend.domain.dream.analysis.StructureResult;
import com.mongle.backend.domain.dream.entity.DreamEmotion;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** 서사화 포트. 기본 구현은 공통 AiGateway를 호출하고 서비스가 출력 검증·저장을 담당한다. */
public interface StoryGenerator {
    default boolean available() {
        return true;
    }

    CompletableFuture<String> generate(Input input);

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
