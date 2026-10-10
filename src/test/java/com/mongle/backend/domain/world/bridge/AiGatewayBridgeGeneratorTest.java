package com.mongle.backend.domain.world.bridge;

import static com.mongle.backend.domain.dream.gateway.DreamAiTestAwait.await;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.ai.dto.request.AiGenerationRequest;
import com.mongle.backend.domain.ai.dto.request.AiMessage;
import com.mongle.backend.domain.ai.dto.response.AiFinishReason;
import com.mongle.backend.domain.ai.dto.response.AiGenerationResult;
import com.mongle.backend.domain.ai.dto.response.AiTokenUsage;
import com.mongle.backend.domain.ai.entity.AiTaskType;
import com.mongle.backend.domain.dream.gateway.DreamAiGateway;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

class AiGatewayBridgeGeneratorTest {
    @Test
    void sendsOnlyTwoStoriesWithWorldBridgeSchemaAndLogContext() {
        var sent = new AtomicReference<AiGenerationRequest>();
        var generator =
                new AiGatewayBridgeGenerator(
                        new DreamAiGateway(
                                request -> {
                                    sent.set(request);
                                    return CompletableFuture.completedFuture(
                                            new AiGenerationResult(
                                                    "{\"content\":\"연결\"}",
                                                    null,
                                                    AiTokenUsage.unknown(),
                                                    AiFinishReason.unknown(),
                                                    null,
                                                    0));
                                },
                                true,
                                1));
        var input = new BridgeGenerator.Input(17L, 29L, "private-attempt", "바다를 보았다.", "숲을 걸었다.");
        assertThat(await(generator.generate(input))).isEqualTo("{\"content\":\"연결\"}");
        var request = sent.get();
        assertThat(request.userId()).isEqualTo(17);
        assertThat(request.taskType()).isEqualTo(AiTaskType.WORLD_BRIDGE);
        assertThat(request.promptVersion()).isEqualTo(BridgePrompt.VERSION);
        assertThat(request.messages()).hasSize(2);
        assertThat(request.messages().getFirst().role()).isEqualTo(AiMessage.Role.SYSTEM);
        assertThat(request.messages().getFirst().content()).isEqualTo(BridgePrompt.system());
        var json = JsonMapper.builder().build();
        var data = json.readTree(request.messages().getLast().content());
        assertThat(data.size()).isEqualTo(2);
        assertThat(data.path("beforeStory").asString()).isEqualTo(input.beforeStory());
        assertThat(data.path("afterStory").asString()).isEqualTo(input.afterStory());
        assertThat(request.messages().getLast().content())
                .doesNotContain("private-attempt", "userId", "bridgeId", "originalText", "worldId");
        assertThat(input.toString()).doesNotContain("바다", "숲");
        assertThat(request.outputSchema().strict()).isTrue();
        assertThat(request.outputSchema().schema()).isEqualTo(json.readTree(BridgePrompt.schema()));
    }

    @Test
    void noConfiguredKeyMeansUnavailable() {
        var generator =
                new AiGatewayBridgeGenerator(
                        new DreamAiGateway(
                                request -> {
                                    throw new AssertionError("외부 호출 금지");
                                },
                                false,
                                1));
        assertThat(generator.available()).isFalse();
    }
}
