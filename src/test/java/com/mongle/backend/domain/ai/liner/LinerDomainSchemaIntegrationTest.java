package com.mongle.backend.domain.ai.liner;

import static com.mongle.backend.domain.dream.gateway.DreamAiTestAwait.await;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mongle.backend.domain.ai.config.*;
import com.mongle.backend.domain.ai.dto.request.AiGenerationRequest;
import com.mongle.backend.domain.ai.entity.AiTaskType;
import com.mongle.backend.domain.ai.liner.client.LinerClient;
import com.mongle.backend.domain.ai.liner.config.*;
import com.mongle.backend.domain.ai.liner.mapper.LinerPayloadMapper;
import com.mongle.backend.domain.ai.service.AiGenerationLogService;
import com.mongle.backend.domain.dream.analysis.*;
import com.mongle.backend.domain.dream.entity.DreamEmotion;
import com.mongle.backend.domain.dream.gateway.DreamAiGateway;
import com.mongle.backend.domain.dream.interpretation.*;
import com.mongle.backend.domain.dream.story.*;
import com.mongle.backend.domain.world.bridge.*;
import com.mongle.backend.domain.world.summary.*;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 실제 Generator → Gateway → HTTP 본문을 로컬 서버에서 관찰한다. 유료 API에 접속하지 않는다. */
class LinerDomainSchemaIntegrationTest {
    private static final Set<String> COMPARISON_REMOVED_KEYWORDS =
            Set.of(
                    "$schema",
                    "minLength",
                    "maxLength",
                    "minItems",
                    "maxItems",
                    "minimum",
                    "maximum",
                    "pattern",
                    "uniqueItems",
                    "allOf",
                    "if",
                    "then",
                    "else");
    private static final List<StructureResult.Scene> SCENES =
            List.of(
                    new StructureResult.Scene(1, "바다를 봤다", false, List.of()),
                    new StructureResult.Scene(2, "숲을 걸었다", true, List.of()));
    private static final String STORY =
            """
            {"sections":[
              {"sequence":1,"kind":"SCENE","sceneSequence":1,"content":"바다를 봤다."},
              {"sequence":2,"kind":"AI_BRIDGE","sceneSequence":null,"content":"풍경이 바뀌었다."},
              {"sequence":3,"kind":"SCENE","sceneSequence":2,"content":"숲을 걸었다."}]}
            """;
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicReference<String> sentBody = new AtomicReference<>();
    private final AtomicReference<AiGenerationRequest> sentRequest = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private HttpServer server;
    private ExecutorService executor;
    private HttpClient client;
    private AiAsyncResources resources;
    private LinerAiGateway liner;
    private DreamAiGateway gateway;

    @BeforeEach
    void startProvider() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext(
                "/chat/completions",
                exchange -> {
                    calls.incrementAndGet();
                    sentBody.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    var body = json.createObjectNode();
                    body.put("model", "liner-mark-1.1");
                    var choice = body.putArray("choices").addObject();
                    choice.put("finish_reason", "stop");
                    choice.putObject("message")
                            .put("role", "assistant")
                            .put("content", response.get());
                    byte[] bytes = json.writeValueAsBytes(body);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(bytes);
                    }
                });
        server.start();
        var properties =
                new LinerProperties(
                        "test-only-key",
                        URI.create(
                                "http://127.0.0.1:"
                                        + server.getAddress().getPort()
                                        + "/chat/completions"),
                        "liner-mark-1.1",
                        4096,
                        "medium",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(5),
                        0,
                        Duration.ofMillis(10),
                        Duration.ofSeconds(1));
        resources = new AiAsyncResources(new AiAsyncProperties(2, 1, 4, 1, 4));
        client = new LinerConfig().linerHttpClient(properties);
        liner =
                new LinerAiGateway(
                        properties,
                        new LinerClient(client, properties, resources),
                        new LinerPayloadMapper(json, properties),
                        mock(AiGenerationLogService.class),
                        resources);
        gateway =
                new DreamAiGateway(
                        request -> {
                            sentRequest.set(request);
                            return liner.generate(request);
                        },
                        true,
                        1);
    }

    @AfterEach
    void closeProvider() {
        if (liner != null) liner.close();
        if (resources != null) resources.close();
        if (client != null) client.shutdownNow();
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @ParameterizedTest
    @EnumSource(
            value = AiTaskType.class,
            names = {
                "DREAM_STRUCTURE",
                "DREAM_NARRATIVE",
                "WORLD_BRIDGE",
                "WORLD_SUMMARY",
                "RECENT_INTERPRETATION"
            })
    void sendsDomainSchemasWithCoreStructureAndWithoutComparisonRemovedConstraints(
            AiTaskType task) {
        response.set(validOutput(task));
        assertThat(await(invoke(task))).isEqualTo(response.get());
        assertThat(calls).hasValue(1);
        var request = sentRequest.get();
        assertThat(request.taskType()).isEqualTo(task);
        var body = json.readTree(sentBody.get());
        assertThat(body.path("model").asString()).isEqualTo("liner-mark-1.1");
        assertThat(body.path("stream").asBoolean()).isFalse();
        assertThat(body.propertyNames())
                .containsExactlyInAnyOrder(
                        "model",
                        "stream",
                        "max_completion_tokens",
                        "reasoning_effort",
                        "messages",
                        "response_format");
        assertThat(body.at("/messages/0/role").asString()).isEqualTo("system");
        assertThat(body.at("/messages/0/content").asString())
                .isEqualTo(request.messages().getFirst().content());
        assertThat(body.at("/messages/1/role").asString()).isEqualTo("user");
        assertThat(body.at("/messages/1/content").asString())
                .isEqualTo(request.messages().getLast().content());
        assertThat(body.at("/response_format/type").asString()).isEqualTo("json_schema");
        var definition = body.at("/response_format/json_schema");
        assertThat(definition.path("strict").asBoolean()).isTrue();
        assertThat(definition.path("name").asString()).isEqualTo(request.outputSchema().name());
        var schema = definition.path("schema");
        assertThat(schema).isEqualTo(request.outputSchema().schema());
        assertThat(comparisonConstraints(schema, "")).isEmpty();
        verifyObjectsAreClosedAndRequired(schema);
        if (task == AiTaskType.DREAM_NARRATIVE) {
            var section = schema.at("/properties/sections/items");
            assertThat(section.path("properties").propertyNames())
                    .containsExactlyInAnyOrder("sequence", "kind", "sceneSequence", "content");
            assertThat(section.at("/properties/sequence/type").asString()).isEqualTo("integer");
            assertThat(section.at("/properties/kind/enum"))
                    .isEqualTo(json.readTree("[\"SCENE\",\"AI_BRIDGE\"]"));
            assertThat(section.at("/properties/sceneSequence/type"))
                    .isEqualTo(json.readTree("[\"integer\",\"null\"]"));
            assertThat(new StoryValidator().parse(response.get(), SCENES).sections()).hasSize(3);
            // 출력/업무 의미와 캐시 키는 유지하고 전송 스키마만 바꾼다.
            assertThat(request.promptVersion()).isEqualTo("story-v2");
        } else if (task != AiTaskType.DREAM_STRUCTURE) {
            String field =
                    task == AiTaskType.WORLD_BRIDGE
                            ? "content"
                            : task == AiTaskType.WORLD_SUMMARY
                                    ? "generatedTitle"
                                    : "interpretation";
            assertThat(schema.path("properties").propertyNames()).containsExactly(field);
            assertThat(schema.path("properties").path(field).path("type").asString())
                    .isEqualTo("string");
            if (task == AiTaskType.WORLD_BRIDGE)
                assertThat(new BridgeValidator().parse(response.get())).isEqualTo("바다에서 숲으로 향했다.");
            if (task == AiTaskType.WORLD_SUMMARY)
                assertThat(new WorldSummaryValidator().title(response.get())).isEqualTo("바다와 숲");
            if (task == AiTaskType.RECENT_INTERPRETATION)
                assertThat(new InterpretationValidator().parse(response.get()))
                        .isEqualTo("관찰 자료가 적습니다. 바다가 등장합니다.");
        }
    }

    @Test
    void successfulHttpResponseStillRequiresJavaSceneAndBridgeValidation() {
        for (String invalid :
                List.of(
                        STORY.replace("\"sceneSequence\":1", "\"sceneSequence\":null"),
                        STORY.replace("\"sceneSequence\":null", "\"sceneSequence\":1"),
                        STORY.replace("\"sceneSequence\":2", "\"sceneSequence\":1"),
                        STORY.replace("\"sequence\":3", "\"sequence\":4"),
                        STORY.replace("바다를 봤다.", "가".repeat(1501)),
                        STORY.replace("바다를 봤다.", "　 "),
                        STORY.replace("\"kind\":\"SCENE\"", "\"kind\":\"UNKNOWN\""))) {
            response.set(invalid);
            var output = await(invoke(AiTaskType.DREAM_NARRATIVE));
            assertThatThrownBy(() -> new StoryValidator().parse(output, SCENES))
                    .isInstanceOfSatisfying(
                            com.mongle.backend.global.error.BusinessException.class,
                            failure -> {
                                assertThat(failure.getErrorCode())
                                        .isEqualTo(StoryErrorCode.INVALID_OUTPUT);
                                assertThat(failure.getCause()).isNull();
                            });
        }
    }

    @Test
    void existingSystemPromptsAlreadyStateTheConstraintsRemovedFromProviderSchemas() {
        assertThat(StoryPrompt.system())
                .contains(
                        "정확히 한 번씩 SCENE",
                        "sceneSequence는 입력 장면 번호",
                        "두 SCENE 사이에 최대 하나",
                        "sceneSequence는 null",
                        "1부터 연속",
                        "1~19개",
                        "1~1500 코드포인트",
                        "6000 코드포인트",
                        "공백만 있는 문단");
        assertThat(BridgePrompt.system()).contains("최대 500자", "짧은 연결 문장", "원문에 없는");
        assertThat(WorldSummaryPrompt.system()).contains("80자 이내", "한 줄", "generatedTitle만");
        assertThat(InterpretationPrompt.system())
                .contains("최대 2000 Unicode 코드포인트", "interpretation", "다른 필드");
        assertThat(BridgePrompt.VERSION).isEqualTo("world-bridge-v1");
        assertThat(WorldSummaryPrompt.VERSION).isEqualTo("world-summary-v1");
        assertThat(InterpretationPrompt.VERSION).isEqualTo("recent-interpretation-v1");
    }

    private CompletableFuture<String> invoke(AiTaskType task) {
        return switch (task) {
            case DREAM_STRUCTURE ->
                    new AiGatewayStructureGenerator(gateway)
                            .generate(
                                    new StructureGenerator.Input(
                                            1L,
                                            2L,
                                            "private-attempt",
                                            "바다를 봤다",
                                            Set.of(DreamEmotion.HAPPY)));
            case DREAM_NARRATIVE ->
                    new AiGatewayStoryGenerator(gateway)
                            .generate(
                                    new StoryGenerator.Input(
                                            1L,
                                            2L,
                                            3L,
                                            "private-attempt",
                                            1,
                                            "바다에서 숲으로 갔다",
                                            Set.of(DreamEmotion.HAPPY),
                                            SCENES,
                                            List.of()));
            case WORLD_BRIDGE ->
                    new AiGatewayBridgeGenerator(gateway)
                            .generate(
                                    new BridgeGenerator.Input(
                                            1L, 2L, "private-attempt", "바다를 봤다.", "숲을 걸었다."));
            case WORLD_SUMMARY ->
                    new AiGatewayWorldSummaryGenerator(gateway)
                            .generate(
                                    new WorldSummaryGenerator.Input(
                                            1L,
                                            new WorldSummaryEvidence.Data(
                                                    List.of(
                                                            new WorldSummaryEvidence.Passage(
                                                                    List.of(
                                                                            new StoryResult.Section(
                                                                                    1,
                                                                                    StoryResult.Kind
                                                                                            .SCENE,
                                                                                    1,
                                                                                    "바다를 봤다.")),
                                                                    null)),
                                                    List.of(),
                                                    List.of(),
                                                    true)));
            case RECENT_INTERPRETATION ->
                    new AiGatewayInterpretationGenerator(gateway)
                            .generate(
                                    new InterpretationGenerator.Input(
                                            1L,
                                            "private-attempt",
                                            new InterpretationTargets.Target(
                                                    LocalDate.of(2026, 10, 1),
                                                    LocalDate.of(2026, 10, 30),
                                                    List.of(),
                                                    "private-fingerprint",
                                                    "[{\"scenes\":[\"바다를 봤다\"]}]")));
            default -> throw new AssertionError(task);
        };
    }

    private String validOutput(AiTaskType task) {
        return switch (task) {
            case DREAM_NARRATIVE -> STORY;
            case WORLD_BRIDGE -> "{\"content\":\"바다에서 숲으로 향했다.\"}";
            case WORLD_SUMMARY -> "{\"generatedTitle\":\"바다와 숲\"}";
            case RECENT_INTERPRETATION -> "{\"interpretation\":\"관찰 자료가 적습니다. 바다가 등장합니다.\"}";
            case DREAM_STRUCTURE ->
                    "{\"generatedTitle\":\"바다\",\"displayKeywords\":[\"바다\"],\"elements\":[],\"scenes\":[{\"sequence\":1,\"content\":\"바다를"
                        + " 봤다\",\"disconnectedFromPrevious\":false,\"elementKeys\":[]}]}";
            default -> throw new AssertionError(task);
        };
    }

    private List<String> comparisonConstraints(JsonNode node, String path) {
        var found = new ArrayList<String>();
        if (node.isObject())
            node.properties()
                    .forEach(
                            field -> {
                                if (COMPARISON_REMOVED_KEYWORDS.contains(field.getKey()))
                                    found.add(path + "/" + field.getKey());
                                found.addAll(
                                        comparisonConstraints(
                                                field.getValue(), path + "/" + field.getKey()));
                            });
        else if (node.isArray())
            node.forEach(child -> found.addAll(comparisonConstraints(child, path + "[]")));
        return found;
    }

    private void verifyObjectsAreClosedAndRequired(JsonNode schema) {
        if (schema.isObject()) {
            if (schema.path("type").isString() && schema.path("type").asString().equals("object")) {
                assertThat(schema.path("additionalProperties").isBoolean()).isTrue();
                assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
                assertThat(schema.path("required").valueStream().map(JsonNode::asString).toList())
                        .containsExactlyInAnyOrderElementsOf(
                                schema.path("properties").propertyNames());
            }
            schema.properties()
                    .forEach(field -> verifyObjectsAreClosedAndRequired(field.getValue()));
        } else if (schema.isArray()) schema.forEach(this::verifyObjectsAreClosedAndRequired);
    }
}
