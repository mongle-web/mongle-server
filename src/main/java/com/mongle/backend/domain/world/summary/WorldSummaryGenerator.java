package com.mongle.backend.domain.world.summary;

import java.util.concurrent.CompletableFuture;

public interface WorldSummaryGenerator {
    default boolean available() {
        return true;
    }

    CompletableFuture<String> generate(Input input);

    record Input(Long userId, WorldSummaryEvidence.Data data) {
        @Override
        public String toString() {
            return "WorldSummaryInput[redacted]";
        }
    }
}
