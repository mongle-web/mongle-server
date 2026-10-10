package com.mongle.backend.domain.dream.story;

import static com.mongle.backend.domain.dream.gateway.DreamAiTestAwait.await;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.ai.dto.request.AiGenerationRequest;
import com.mongle.backend.domain.ai.dto.request.AiMessage;
import com.mongle.backend.domain.ai.dto.response.AiFinishReason;
import com.mongle.backend.domain.ai.dto.response.AiGenerationResult;
import com.mongle.backend.domain.ai.dto.response.AiTokenUsage;
import com.mongle.backend.domain.ai.entity.AiTaskType;
import com.mongle.backend.domain.dream.analysis.StructureResult;
import com.mongle.backend.domain.dream.entity.DreamEmotion;
import com.mongle.backend.domain.dream.entity.DreamEntityType;
import com.mongle.backend.domain.dream.gateway.DreamAiGateway;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

class AiGatewayStoryGeneratorTest {

    @Test
    void usesStoryContractAndPreservesSceneOrderWithoutSendingInternalIdentifiers() {
        var sent = new AtomicReference<AiGenerationRequest>();
        var gateway =
                new DreamAiGateway(
                        request -> {
                            sent.set(request);
                            return java.util.concurrent.CompletableFuture.completedFuture(
                                    new AiGenerationResult(
                                            StoryValidatorTest.VALID,
                                            null,
                                            AiTokenUsage.unknown(),
                                            AiFinishReason.unknown(),
                                            null,
                                            0));
                        },
                        true,
                        1);
        var generator = new AiGatewayStoryGenerator(gateway);
        var scenes =
                List.of(
                        new StructureResult.Scene(1, "바다 위를 날았다", false, List.of("e937")),
                        new StructureResult.Scene(2, "숲길을 걸었다", true, List.of()));
        var elements =
                List.of(
                        new StructureResult.Element(
                                "e937", DreamEntityType.PLACE, "바다", "꿈에 나온 바다"));
        var input =
                new StoryGenerator.Input(
                        17L,
                        21L,
                        29L,
                        "private-attempt",
                        3,
                        "바다 위를 날다가 숲길을 걸었다",
                        Set.of(DreamEmotion.HAPPY),
                        scenes,
                        elements);

        assertThat(generator.available()).isTrue();
        assertThat(await(generator.generate(input))).isEqualTo(StoryValidatorTest.VALID);
        var request = sent.get();
        assertThat(request.userId()).isEqualTo(17);
        assertThat(request.taskType()).isEqualTo(AiTaskType.DREAM_NARRATIVE);
        assertThat(request.promptVersion()).isEqualTo("story-v2");
        assertThat(request.messages()).hasSize(2);
        assertThat(request.messages().get(0).role()).isEqualTo(AiMessage.Role.SYSTEM);
        assertThat(request.messages().get(0).content()).isEqualTo(StoryPrompt.system());
        assertThat(request.messages().get(1).role()).isEqualTo(AiMessage.Role.USER);
        var json = JsonMapper.builder().build();
        var data = json.readTree(request.messages().get(1).content());
        assertThat(data.size()).isEqualTo(4);
        assertThat(data.path("originalText").asString()).isEqualTo(input.originalText());
        assertThat(data.at("/scenes/0/sequence").asInt()).isEqualTo(1);
        assertThat(data.at("/scenes/1/sequence").asInt()).isEqualTo(2);
        assertThat(data.at("/scenes/0/content").asString()).isEqualTo(scenes.get(0).content());
        assertThat(data.at("/scenes/1/disconnectedFromPrevious").asBoolean()).isTrue();
        assertThat(data.at("/scenes/0/elementKeys/0").asString()).isEqualTo("element_1");
        assertThat(data.at("/elements/0/key").asString()).isEqualTo("element_1");
        assertThat(data.at("/elements/0/name").asString()).isEqualTo("바다");
        assertThat(input.scenes().get(0).elementKeys()).containsExactly("e937");
        assertThat(request.messages().get(1).content())
                .doesNotContain(
                        "private-attempt",
                        "storyId",
                        "analysisId",
                        "sourceRevision",
                        "userId",
                        "e937");
        assertThat(request.outputSchema().strict()).isTrue();
        assertThat(request.outputSchema().name()).isEqualTo("dream_story");
        assertThat(request.outputSchema().schema()).isEqualTo(json.readTree(StoryPrompt.schema()));
        assertThat(new StoryValidator().parse(await(generator.generate(input)), scenes).sections())
                .hasSize(3);
    }

    @Test
    void unavailableWithoutKey() {
        var generator =
                new AiGatewayStoryGenerator(
                        new DreamAiGateway(
                                request -> {
                                    throw new AssertionError("외부 호출 금지");
                                },
                                false,
                                1));

        assertThat(generator.available()).isFalse();
    }
}
