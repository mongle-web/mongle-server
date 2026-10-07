package com.mongle.backend.domain.dream.image;

import com.mongle.backend.domain.dream.story.StoryResult;

import java.util.List;

/** 전용 이미지 Provider 어댑터 포트. 호출·타임아웃·인증·요금은 어댑터에서 처리한다. */
public interface ImageGenerator {
    default boolean available() {
        return true;
    }

    byte[] generate(Input input);

    record Input(
            Long userId,
            Long imageId,
            String attemptId,
            String promptVersion,
            String style,
            String mood,
            List<StoryResult.Section> sections) {
        public Input {
            sections = List.copyOf(sections);
        }

        @Override
        public String toString() {
            return "ImageInput[imageId=" + imageId + ",attemptId=" + attemptId + "]";
        }
    }
}
