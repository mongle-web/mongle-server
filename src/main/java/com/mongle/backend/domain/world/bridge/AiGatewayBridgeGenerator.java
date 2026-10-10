package com.mongle.backend.domain.world.bridge;

import com.mongle.backend.domain.ai.dto.request.AiGenerationRequest;
import com.mongle.backend.domain.ai.dto.request.AiJsonSchema;
import com.mongle.backend.domain.ai.dto.request.AiMessage;
import com.mongle.backend.domain.ai.entity.AiTaskType;
import com.mongle.backend.domain.dream.gateway.DreamAiGateway;

import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class AiGatewayBridgeGenerator implements BridgeGenerator {
    private final DreamAiGateway gateway;
    private final JsonMapper json = JsonMapper.builder().build();
    private final AiMessage system = new AiMessage(AiMessage.Role.SYSTEM, BridgePrompt.system());
    private final AiJsonSchema schema =
            new AiJsonSchema("world_bridge", json.readTree(BridgePrompt.schema()), true);

    public AiGatewayBridgeGenerator(DreamAiGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public boolean available() {
        return gateway.available();
    }

    @Override
    public CompletableFuture<String> generate(Input input) {
        // ID·원문·다른 세계관 내용은 메시지에 넣지 않는다. userId는 Gateway 로그에만 사용한다.
        var data = Map.of("beforeStory", input.beforeStory(), "afterStory", input.afterStory());
        return gateway.generate(
                new AiGenerationRequest(
                        input.userId(),
                        AiTaskType.WORLD_BRIDGE,
                        BridgePrompt.VERSION,
                        List.of(
                                system,
                                new AiMessage(AiMessage.Role.USER, json.writeValueAsString(data))),
                        schema));
    }
}
