package com.mongle.backend.domain.dream.analysis;

import com.mongle.backend.domain.ai.dto.request.AiGenerationRequest;
import com.mongle.backend.domain.ai.dto.request.AiJsonSchema;
import com.mongle.backend.domain.ai.dto.request.AiMessage;
import com.mongle.backend.domain.ai.entity.AiTaskType;
import com.mongle.backend.domain.dream.gateway.DreamAiGateway;

import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class AiGatewayStructureGenerator implements StructureGenerator {

    private final DreamAiGateway gateway;
    private final JsonMapper json = JsonMapper.builder().build();
    private final AiMessage system = new AiMessage(AiMessage.Role.SYSTEM, StructurePrompt.system());
    private final AiJsonSchema schema =
            new AiJsonSchema("dream_structure", json.readTree(StructurePrompt.schema()), true);

    public AiGatewayStructureGenerator(DreamAiGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public boolean available() {
        return gateway.available();
    }

    @Override
    public CompletableFuture<String> generate(Input input) {
        // 사용자 원문은 지시문에 합치지 않고 JSON 데이터 메시지로 전달한다.
        var data =
                Map.of(
                        "originalText", input.originalText(),
                        "emotions", input.emotions().stream().sorted().toList());
        var user = new AiMessage(AiMessage.Role.USER, json.writeValueAsString(data));
        return gateway.generate(
                new AiGenerationRequest(
                        input.userId(),
                        AiTaskType.DREAM_STRUCTURE,
                        StructurePrompt.VERSION,
                        List.of(system, user),
                        schema));
    }
}
