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
        assertThat(request.promptVersion()).isEqualTo("scene-v2-display-limits");
        assertThat(request.messages()).hasSize(2);
        assertThat(request.messages().get(0).role()).isEqualTo(AiMessage.Role.SYSTEM);
        assertThat(request.messages().get(0).content()).isEqualTo(StructurePrompt.system());
        // 서버가 거절하는 길이·개수·식별자 규칙을 실제 SYSTEM 메시지에도 전달해야 한다.
        assertThat(request.messages().get(0).content())
                .contains(
                        "전체 장면은 1~10개",
                        "각 장면 content는 1~1000 Unicode 코드포인트",
                        "장면 sequence는 1~10 범위에서 1부터 시작하는 연속 정수",
                        "요소 elements는 0~30개",
                        "요소 key는 1~40자의 고유한 값이며 영문자로 시작",
                        "^[a-zA-Z][a-zA-Z0-9_-]{0,39}$",
                        "요소 name은 1~100 Unicode 코드포인트",
                        "description은 1~500 Unicode 코드포인트",
                        "key·name·description·content는 앞뒤 공백 없이 작성",
                        "elementKeys는 0~30개",
                        "존재하는 요소 key만 중복 없이 그대로 참조");
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
        // 운영 LINER 비교 호출에서 제약을 제거한 요청만 200/stop으로 완료됐다.
        // 길이·범위·중복 규칙은 StructureValidator가 검증하고, 전송 스키마는 구조만 제한한다.
        assertThat(request.outputSchema().schema().toString())
                .doesNotContain(
                        "\"uniqueItems\"",
                        "\"pattern\"",
                        "\"minLength\"",
                        "\"maxLength\"",
                        "\"minItems\"",
                        "\"maxItems\"",
                        "\"minimum\"",
                        "\"maximum\"");
        assertThat(request.outputSchema().schema().at("/properties/elements/items/properties/type/enum"))
                .isEqualTo(json.readTree("[\"CHARACTER\",\"PLACE\",\"SYMBOL\",\"EMOTION\"]"));
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
