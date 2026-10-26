package com.mongle.backend.domain.dream.image;

import com.mongle.backend.domain.dream.story.StoryResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 전용 이미지 Provider 어댑터 포트. 호출·타임아웃·인증·요금은 어댑터에서 처리한다. */
public interface ImageGenerator {
    default boolean available() {
        return true;
    }

    /** 외부 대기는 비동기 HTTP로 처리한다. 전체 호출 시간은 2분의 시도 lease보다 짧아야 한다. */
    CompletableFuture<byte[]> generate(Input input);

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
