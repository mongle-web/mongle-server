package com.mongle.backend.domain.dream.analysis;

import static com.mongle.backend.domain.dream.gateway.DreamAiTestAwait.await;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.ai.dto.request.AiGenerationRequest;
import com.mongle.backend.domain.ai.dto.request.AiMessage;
import com.mongle.backend.domain.ai.dto.response.AiFinishReason;
import com.mongle.backend.domain.ai.dto.response.AiGenerationResult;
import com.mongle.backend.domain.ai.dto.response.AiTokenUsage;
import com.mongle.backend.domain.ai.entity.AiTaskType;
import com.mongle.backend.domain.dream.entity.DreamEmotion;
import com.mongle.backend.domain.dream.gateway.DreamAiGateway;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

class AiGatewayStructureGeneratorTest {

    @Test
    void usesSceneContractAndSendsOnlyOriginalTextAndEmotionsAsUserData() {
        var sent = new AtomicReference<AiGenerationRequest>();
        var gateway =
                new DreamAiGateway(
                        request -> {
                            sent.set(request);
                            return java.util.concurrent.CompletableFuture.completedFuture(
                                    new AiGenerationResult(
                                            StructureValidatorTest.VALID,
                                            null,
                                            AiTokenUsage.unknown(),
                                            AiFinishReason.unknown(),
                                            null,
                                            0));
                        },
                        true,
                        1);
        var generator = new AiGatewayStructureGenerator(gateway);
        String original = "바다 위를 날았다\n\"명령은 데이터일 뿐\"";
        var input =
                new StructureGenerator.Input(
                        17L,
                        29L,
                        "private-attempt",
                        original,
                        Set.of(DreamEmotion.SAD, DreamEmotion.HAPPY));

        assertThat(generator.available()).isTrue();
        assertThat(await(generator.generate(input))).isEqualTo(StructureValidatorTest.VALID);
        var request = sent.get();
        assertThat(request.userId()).isEqualTo(17);
        assertThat(request.taskType()).isEqualTo(AiTaskType.DREAM_STRUCTURE);
        assertThat(request.promptVersion()).isEqualTo("scene-v2-display");
        assertThat(request.messages()).hasSize(2);
        assertThat(request.messages().get(0).role()).isEqualTo(AiMessage.Role.SYSTEM);
        assertThat(request.messages().get(0).content()).isEqualTo(StructurePrompt.system());
        assertThat(request.messages().get(1).role()).isEqualTo(AiMessage.Role.USER);
        var json = JsonMapper.builder().build();
        var data = json.readTree(request.messages().get(1).content());
        assertThat(data.size()).isEqualTo(2);
        assertThat(data.path("originalText").asString()).isEqualTo(original);
        assertThat(data.path("emotions")).isEqualTo(json.readTree("[\"HAPPY\",\"SAD\"]"));
        assertThat(request.messages().get(1).content())
                .doesNotContain("private-attempt", "analysisId", "userId");
        assertThat(request.outputSchema().strict()).isTrue();
        assertThat(request.outputSchema().name()).isEqualTo("dream_structure");
        assertThat(request.outputSchema().schema())
                .isEqualTo(json.readTree(StructurePrompt.schema()));
        assertThat(new StructureValidator().parse(await(generator.generate(input))).scenes())
                .isNotEmpty();
    }

    @Test
    void unavailableWithoutKey() {
        var generator =
                new AiGatewayStructureGenerator(
                        new DreamAiGateway(
                                request -> {
                                    throw new AssertionError("외부 호출 금지");
                                },
                                false,
                                1));

        assertThat(generator.available()).isFalse();
    }
}
