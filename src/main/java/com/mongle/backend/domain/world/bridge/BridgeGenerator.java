package com.mongle.backend.domain.world.bridge;

import java.util.concurrent.CompletableFuture;

/** 두 성공 서사 사이의 연결부만 생성한다. 원문·세계관 전체 내용은 입력하지 않는다. */
public interface BridgeGenerator {
    default boolean available() { return true; }

    CompletableFuture<String> generate(Input input);

    record Input(Long userId, Long bridgeId, String attemptId, String beforeStory, String afterStory) {
        @Override
        public String toString() {
            return "BridgeInput[bridgeId=" + bridgeId + "]";
        }
    }
}
