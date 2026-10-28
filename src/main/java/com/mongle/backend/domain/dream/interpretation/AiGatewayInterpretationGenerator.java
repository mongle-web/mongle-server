package com.mongle.backend.domain.dream.interpretation;

import com.mongle.backend.domain.ai.dto.request.*;
import com.mongle.backend.domain.ai.entity.AiTaskType;
import com.mongle.backend.domain.dream.gateway.DreamAiGateway;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class AiGatewayInterpretationGenerator implements InterpretationGenerator {
    private final DreamAiGateway gateway;
    private final JsonMapper json = JsonMapper.builder().build();
    private final AiMessage system = new AiMessage(AiMessage.Role.SYSTEM, InterpretationPrompt.system());
    private final AiJsonSchema schema = new AiJsonSchema("recent_interpretation", json.readTree(InterpretationPrompt.schema()), true);
    public AiGatewayInterpretationGenerator(DreamAiGateway gateway) { this.gateway = gateway; }
    @Override public boolean available() { return gateway.available(); }
    @Override public CompletableFuture<String> generate(Input input) {
        var target = input.target();
        var data = Map.of("dreams", json.readTree(target.evidenceJson()),
                "limitedEvidence", target.limitedEvidence());
        return gateway.generate(new AiGenerationRequest(input.userId(), AiTaskType.RECENT_INTERPRETATION,
                InterpretationPrompt.VERSION, List.of(system,
                new AiMessage(AiMessage.Role.USER, json.writeValueAsString(data))), schema));
    }
}
