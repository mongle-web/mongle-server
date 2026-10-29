package com.mongle.backend.domain.world.summary;

import com.mongle.backend.domain.ai.dto.request.*;
import com.mongle.backend.domain.ai.entity.AiTaskType;
import com.mongle.backend.domain.dream.gateway.DreamAiGateway;

import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class AiGatewayWorldSummaryGenerator implements WorldSummaryGenerator {
    private final DreamAiGateway gateway;
    private final JsonMapper json = JsonMapper.builder().build();
    private final AiMessage system =
            new AiMessage(AiMessage.Role.SYSTEM, WorldSummaryPrompt.system());
    private final AiJsonSchema schema =
            new AiJsonSchema("world_summary", json.readTree(WorldSummaryPrompt.schema()), true);

    public AiGatewayWorldSummaryGenerator(DreamAiGateway gateway) {
        this.gateway = gateway;
    }

    public boolean available() {
        return gateway.available();
    }

    public CompletableFuture<String> generate(Input input) {
        return gateway.generate(
                new AiGenerationRequest(
                        input.userId(),
                        AiTaskType.WORLD_SUMMARY,
                        WorldSummaryPrompt.VERSION,
                        List.of(
                                system,
                                new AiMessage(
                                        AiMessage.Role.USER,
                                        json.writeValueAsString(input.data()))),
                        schema));
    }
}
