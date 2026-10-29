package com.mongle.backend.domain.world.summary;

import static com.mongle.backend.domain.dream.gateway.DreamAiTestAwait.await;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.ai.dto.request.*;
import com.mongle.backend.domain.ai.dto.response.*;
import com.mongle.backend.domain.ai.entity.AiTaskType;
import com.mongle.backend.domain.dream.gateway.DreamAiGateway;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

class AiGatewayWorldSummaryGeneratorTest {
    @Test
    void sendsOnlyPublishedPassagesAndDeterministicEvidenceWithStrictSchema() {
        var sent = new AtomicReference<AiGenerationRequest>();
        var generator =
                new AiGatewayWorldSummaryGenerator(
                        new DreamAiGateway(
                                request -> {
                                    sent.set(request);
                                    return CompletableFuture.completedFuture(
                                            new AiGenerationResult(
                                                    "{\"generatedTitle\":\"바다\"}",
                                                    null,
                                                    AiTokenUsage.unknown(),
                                                    AiFinishReason.unknown(),
                                                    null,
                                                    0));
                                },
                                true,
                                1));
        var data =
                new WorldSummaryEvidence.Data(
                        List.of(
                                new WorldSummaryEvidence.Passage(
                                        List.of(
                                                new com.mongle.backend.domain.dream.story
                                                        .StoryResult.Section(
                                                        1,
                                                        com.mongle.backend.domain.dream.story
                                                                .StoryResult.Kind.SCENE,
                                                        1,
                                                        "고정 서사")),
                                        null)),
                        List.of(),
                        List.of(),
                        true);
        var input = new WorldSummaryGenerator.Input(17L, data);
        assertThat(await(generator.generate(input))).contains("바다");
        var request = sent.get();
        assertThat(request.userId()).isEqualTo(17);
        assertThat(request.taskType()).isEqualTo(AiTaskType.WORLD_SUMMARY);
        assertThat(request.promptVersion()).isEqualTo(WorldSummaryPrompt.VERSION);
        assertThat(request.outputSchema().strict()).isTrue();
        assertThat(request.outputSchema().schema().path("additionalProperties").asBoolean())
                .isFalse();
        assertThat(request.messages().getFirst().role()).isEqualTo(AiMessage.Role.SYSTEM);
        assertThat(request.messages().getLast().content())
                .contains("고정 서사", "commonKeywords", "recurringEmotions")
                .doesNotContain("userId", "dreamId", "worldId", "originalText", "settings");
        assertThat(input.toString() + data.toString()).doesNotContain("고정 서사");
    }

    @Test
    void unconfiguredGatewayIsUnavailable() {
        var generator =
                new AiGatewayWorldSummaryGenerator(
                        new DreamAiGateway(
                                request -> {
                                    throw new AssertionError("호출 금지");
                                },
                                false,
                                1));
        assertThat(generator.available()).isFalse();
    }
}
