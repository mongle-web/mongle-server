package com.mongle.backend.domain.dream.story;

import com.mongle.backend.domain.ai.dto.request.AiGenerationRequest;
import com.mongle.backend.domain.ai.dto.request.AiJsonSchema;
import com.mongle.backend.domain.ai.dto.request.AiMessage;
import com.mongle.backend.domain.ai.entity.AiTaskType;
import com.mongle.backend.domain.dream.analysis.StructureResult;
import com.mongle.backend.domain.dream.gateway.DreamAiGateway;

import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class AiGatewayStoryGenerator implements StoryGenerator {

    private final DreamAiGateway gateway;
    private final JsonMapper json = JsonMapper.builder().build();
    private final AiMessage system = new AiMessage(AiMessage.Role.SYSTEM, StoryPrompt.system());
    private final AiJsonSchema schema =
            new AiJsonSchema("dream_story", json.readTree(StoryPrompt.schema()), true);

    public AiGatewayStoryGenerator(DreamAiGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public boolean available() {
        return gateway.available();
    }

    @Override
    public CompletableFuture<String> generate(Input input) {
        // 저장된 분석의 e{DB ID} 참조를 이번 요청 안에서만 쓰는 키로 바꾼다.
        var keys = new HashMap<String, String>();
        var elements = new ArrayList<StructureResult.Element>();
        for (var element : input.elements()) {
            String key = "element_" + (elements.size() + 1);
            keys.put(element.key(), key);
            elements.add(
                    new StructureResult.Element(
                            key, element.type(), element.name(), element.description()));
        }
        var scenes =
                input.scenes().stream()
                        .map(
                                scene ->
                                        new StructureResult.Scene(
                                                scene.sequence(),
                                                scene.content(),
                                                scene.disconnectedFromPrevious(),
                                                scene.elementKeys().stream()
                                                        .map(
                                                                key ->
                                                                        Objects.requireNonNull(
                                                                                keys.get(key),
                                                                                "장면이 참조하는 요소가"
                                                                                    + " 없습니다."))
                                                        .toList()))
                        .toList();
        var data =
                Map.of(
                        "originalText",
                        input.originalText(),
                        "emotions",
                        input.emotions().stream().sorted().toList(),
                        "scenes",
                        scenes,
                        "elements",
                        elements);
        var user = new AiMessage(AiMessage.Role.USER, json.writeValueAsString(data));
        return gateway.generate(
                new AiGenerationRequest(
                        input.userId(),
                        AiTaskType.DREAM_NARRATIVE,
                        StoryPrompt.VERSION,
                        List.of(system, user),
                        schema));
    }
}
