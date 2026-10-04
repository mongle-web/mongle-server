package com.mongle.backend.domain.dream.analysis;

import com.mongle.backend.domain.dream.entity.DreamEmotion;
import java.util.Set;

/** 도메인 포트. #5의 AiGateway 계약 확정 후 어댑터에서 공통 Gateway를 호출한다. */
public interface StructureGenerator {
    default boolean available() {
        return true;
    }

    String generate(Input input);

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
