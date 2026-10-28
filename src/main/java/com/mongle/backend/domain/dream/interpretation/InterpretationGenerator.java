package com.mongle.backend.domain.dream.interpretation;

import java.util.concurrent.CompletableFuture;

public interface InterpretationGenerator {
    default boolean available() { return true; }
    CompletableFuture<String> generate(Input input);
    record Input(Long userId, String attemptId, InterpretationTargets.Target target) {
        @Override public String toString() { return "InterpretationInput[attemptId="+attemptId+"]"; }
    }
}
