package com.mongle.backend.domain.ai.dto;

import com.mongle.backend.domain.ai.dto.request.AiGenerationRequest;
import com.mongle.backend.domain.ai.dto.request.AiJsonSchema;
import com.mongle.backend.domain.ai.dto.request.AiMessage;
import com.mongle.backend.domain.ai.dto.response.AiFinishReason;
import com.mongle.backend.domain.ai.dto.response.AiGenerationResult;
import com.mongle.backend.domain.ai.dto.response.AiTokenUsage;
import com.mongle.backend.domain.ai.entity.AiTaskType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 외부 호출을 만들기 전에 계약이 잘못된 입력과 메타데이터 변조를 차단하는지 확인한다. */
class AiGatewayDtoTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void requestPreservesContextAndMessagesWhenOriginalListChanges() {
        AiMessage instruction = new AiMessage(AiMessage.Role.SYSTEM, "  지시문\n원문 유지  ");
        AiMessage input = new AiMessage(AiMessage.Role.USER, "테스트 입력");
        List<AiMessage> original = new ArrayList<>(List.of(instruction, input));
        AiGenerationRequest request = new AiGenerationRequest(
                7L, AiTaskType.DREAM_STRUCTURE, "fixture-v1", original, null);
        original.clear();

        assertThat(request.userId()).isEqualTo(7L);
        assertThat(request.taskType()).isEqualTo(AiTaskType.DREAM_STRUCTURE);
        assertThat(request.promptVersion()).isEqualTo("fixture-v1");
        assertThat(request.messages()).containsExactly(instruction, input);
        assertThat(request.messages().getFirst().content()).isEqualTo("  지시문\n원문 유지  ");
        assertThat(request.outputSchema()).isNull();
        assertThatThrownBy(() -> request.messages().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void schemaIsIsolatedFromMutationsOfOriginalAndReturnedNestedTrees() {
        ObjectNode original = (ObjectNode) mapper.readTree("""
                {"type":"object","properties":{"value":{"type":"string"}}}
                """);
        AiJsonSchema schema = new AiJsonSchema("fixture_output", original, true);
        ((ObjectNode) original.at("/properties/value")).put("type", "number");
        ((ObjectNode) schema.schema().at("/properties/value")).put("type", "boolean");

        assertThat(schema.schema().at("/properties/value/type").asString()).isEqualTo("string");
        assertThat(schema.name()).isEqualTo("fixture_output");
        assertThat(schema.strict()).isTrue();
    }

    @Test
    void rejectsMissingCallContextBeforeAnyExternalCallCanBeMade() {
        List<AiMessage> messages = List.of(new AiMessage(AiMessage.Role.USER, "입력"));
        assertThatThrownBy(() -> new AiGenerationRequest(0, AiTaskType.DREAM_STRUCTURE, "v1", messages, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("사용자 ID");
        assertThatThrownBy(() -> new AiGenerationRequest(1, null, "v1", messages, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("작업 종류");
        assertThatThrownBy(() -> new AiGenerationRequest(1, AiTaskType.DREAM_STRUCTURE, " ", messages, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("프롬프트 버전");
        assertThatThrownBy(() -> new AiGenerationRequest(1, AiTaskType.DREAM_STRUCTURE, "v1", List.of(), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("메시지 목록");
        List<AiMessage> withNull = new ArrayList<>(messages);
        withNull.add(null);
        assertThatThrownBy(() -> new AiGenerationRequest(1, AiTaskType.DREAM_STRUCTURE, "v1", withNull, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AiMessage(AiMessage.Role.USER, "\n "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("내용");
        assertThatThrownBy(() -> new AiMessage(null, "입력"))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("메시지 역할");
    }

    @Test
    void rejectsOutputOptionsThatAreNotNamedJsonObjects() {
        assertThatThrownBy(() -> new AiJsonSchema(" ", mapper.createObjectNode(), true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("이름");
        assertThatThrownBy(() -> new AiJsonSchema("fixture", mapper.readTree("[]"), true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("JSON 객체");
    }

    @Test
    void distinguishesUnknownUsageFromReportedZeroAndPreservesPartialUsage() {
        AiTokenUsage unknown = AiTokenUsage.unknown();
        AiTokenUsage zero = new AiTokenUsage(0, 0, 0, 0, 0);
        AiTokenUsage partial = new AiTokenUsage(100, 20, null, null, null);

        assertThat(unknown).isNotEqualTo(zero);
        assertThat(unknown.inputTokens()).isNull();
        assertThat(unknown.outputTokens()).isNull();
        assertThat(unknown.totalTokens()).isNull();
        assertThat(unknown.cachedInputTokens()).isNull();
        assertThat(unknown.reasoningTokens()).isNull();
        assertThat(zero.inputTokens()).isZero();
        assertThat(zero.cachedInputTokens()).isZero();
        // 합계를 계산할 수 있어도 제공자가 보고하지 않았다면 추정하지 않고 null을 유지한다.
        assertThat(partial.totalTokens()).isNull();
        assertThat(partial.cachedInputTokens()).isNull();
    }

    @Test
    void cachedAndReasoningTokensRemainSubsetsOfInputAndOutput() {
        AiTokenUsage usage = new AiTokenUsage(100, 50, 150, 25, 30);
        assertThat(usage.inputTokens()).isEqualTo(100);
        assertThat(usage.outputTokens()).isEqualTo(50);
        assertThat(usage.totalTokens()).isEqualTo(150);
        assertThat(usage.cachedInputTokens()).isEqualTo(25);
        assertThat(usage.reasoningTokens()).isEqualTo(30);
    }

    @ParameterizedTest
    @MethodSource("invalidUsage")
    void rejectsUsageThatWouldProduceIncorrectAccounting(
            Integer input, Integer output, Integer total, Integer cached, Integer reasoning
    ) {
        assertThatThrownBy(() -> new AiTokenUsage(input, output, total, cached, reasoning))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> invalidUsage() {
        return Stream.of(
                Arguments.of(-1, null, null, null, null),
                Arguments.of(null, -1, null, null, null),
                Arguments.of(null, null, -1, null, null),
                Arguments.of(null, null, null, -1, null),
                Arguments.of(null, null, null, null, -1),
                Arguments.of(10, null, null, 11, null),
                Arguments.of(null, 10, null, null, 11),
                Arguments.of(10, 20, 31, null, null)
        );
    }

    @ParameterizedTest
    @CsvSource({
            "stop, STOP",
            "length, OUTPUT_LIMIT",
            "tool_calls, TOOL_CALLS",
            "content_filter, CONTENT_FILTER",
            "future_provider_reason, OTHER"
    })
    void classifiesFinishReasonWithoutDiscardingProviderValue(String raw, AiFinishReason.Type expected) {
        AiFinishReason reason = new AiFinishReason(raw);
        assertThat(reason.type()).isEqualTo(expected);
        assertThat(reason.providerValue()).isEqualTo(raw);
    }

    @Test
    void missingFinishReasonIsNotAssumedToBeNormalStop() {
        assertThat(AiFinishReason.unknown().type()).isEqualTo(AiFinishReason.Type.UNKNOWN);
        assertThat(AiFinishReason.unknown().providerValue()).isNull();
        assertThatThrownBy(() -> new AiFinishReason(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesContentAndAllowsUnavailableResponseMetadata() {
        AiGenerationResult result = new AiGenerationResult(
                " \n{\"value\":\"ok\"}\n ", null, AiTokenUsage.unknown(), AiFinishReason.unknown(), null, 0);
        assertThat(result.content()).isEqualTo(" \n{\"value\":\"ok\"}\n ");
        assertThat(result.modelName()).isNull();
        assertThat(result.requestId()).isNull();
        assertThat(result.latencyMs()).isZero();
    }

    @Test
    void rejectsEmptyContentAndInvalidResponseMetadata() {
        assertThatThrownBy(() -> result(" ", "fixture-model", "fixture-id", 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("내용");
        assertThatThrownBy(() -> result("ok", " ", "fixture-id", 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("모델명");
        assertThatThrownBy(() -> result("ok", "fixture-model", " ", 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("외부 요청 ID");
        assertThatThrownBy(() -> result("ok", "fixture-model", "fixture-id", -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("소요 시간");
        assertThatThrownBy(() -> new AiGenerationResult("ok", null, null, AiFinishReason.unknown(), null, 0))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("토큰 사용량");
        assertThatThrownBy(() -> new AiGenerationResult("ok", null, AiTokenUsage.unknown(), null, null, 0))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("종료 사유");
    }

    private AiGenerationResult result(String content, String model, String requestId, long latencyMs) {
        return new AiGenerationResult(content, model, AiTokenUsage.unknown(), AiFinishReason.unknown(), requestId, latencyMs);
    }
}
