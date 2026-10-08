package com.mongle.backend.domain.dream.analysis;

import com.mongle.backend.domain.dream.entity.DreamEmotion;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** 장면 분석 포트. 기본 구현은 공통 AiGateway를 호출하고 서비스가 출력 검증·저장을 담당한다. */
public interface StructureGenerator {
    default boolean available() {
        return true;
    }

    CompletableFuture<String> generate(Input input);

    record Input(
            Long userId,
            Long analysisId,
            String attemptId,
            String originalText,
            Set<DreamEmotion> emotions) {
        public Input {
            emotions = Set.copyOf(emotions);
        }

        // 꿈 원문이 객체의 기본 문자열을 통해 로그에 유출되지 않도록 한다.
        @Override
        public String toString() {
            return "StructureInput[analysisId=" + analysisId + ",attemptId=" + attemptId + "]";
        }
    }
}
