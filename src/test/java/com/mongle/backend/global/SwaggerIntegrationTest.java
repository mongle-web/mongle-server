package com.mongle.backend.global;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongle.backend.domain.dream.analysis.AnalysisRecordService;
import com.mongle.backend.domain.dream.entity.DreamEmotion;
import com.mongle.backend.domain.world.folder.WorldFolderCardResponse;
import com.mongle.backend.domain.world.folder.WorldFolderPage;
import com.mongle.backend.domain.world.summary.WorldSummaryContent;
import com.mongle.backend.domain.world.summary.WorldSummaryResponse;
import com.mongle.backend.global.config.SwaggerConfig;
import com.mongle.backend.global.response.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({
    SwaggerIntegrationTest.ConventionController.class,
    SwaggerIntegrationTest.ConventionSecurity.class
})
class SwaggerIntegrationTest {

    // 테스트용 API만 공개하며, 실제 업무 API의 접근 제한은 유지한다.
    @TestConfiguration(proxyBeanMethods = false)
    static class ConventionSecurity {
        @Bean
        @Order(0)
        SecurityFilterChain conventionChain(HttpSecurity http) throws Exception {
            return http.securityMatcher("/api/v1/setup-test")
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .build();
        }
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired private ObjectMapper objectMapper;

    @Test
    void servesOpenApiWithJwtSchemeAndInterfaceAnnotations() throws Exception {
        HttpResponse<String> response = get("/v3/api-docs");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode document = objectMapper.readTree(response.body());
        assertThat(document.at("/info/title").asString()).isEqualTo("몽글 API");
        assertThat(document.at("/components/securitySchemes/bearerAuth/type").asString())
                .isEqualTo("http");
        assertThat(document.at("/components/securitySchemes/bearerAuth/scheme").asString())
                .isEqualTo("bearer");
        assertThat(document.at("/components/securitySchemes/bearerAuth/bearerFormat").asString())
                .isEqualTo("JWT");
        JsonNode operation = document.at("/paths/~1api~1v1~1setup-test/get");
        assertThat(operation.get("summary").asString()).isEqualTo("문서화 규칙 검증");
        assertThat(operation.at("/tags/0").asString()).isEqualTo("SetupTest");
        assertThat(operation.at("/security/0/bearerAuth").isArray()).isTrue();
        assertThat(operation.at("/responses/200/content/application~1json/schema/$ref").asString())
                .as("OpenAPI response: %s", response.body())
                .contains("ApiResponse");
    }

    @Test
    void servesSwaggerUiAndCommonResponseEnvelope() throws Exception {
        HttpResponse<String> ui = get("/swagger-ui/index.html");
        assertThat(ui.statusCode()).isEqualTo(200);
        assertThat(ui.body()).contains("Swagger UI");
        HttpResponse<String> response = get("/api/v1/setup-test");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("success").asBoolean()).isTrue();
        assertThat(body.get("code").asString()).isEqualTo("SUCCESS");
        assertThat(body.at("/data/value").asString()).isEqualTo("ok");
    }

    @Test
    void monthlyAndWorldSummaryUseDistinctSchemasIncludingNestedWorldCards() throws Exception {
        var response = get("/v3/api-docs");
        assertThat(response.statusCode()).isEqualTo(200);
        var document = objectMapper.readTree(response.body());
        var monthly = responseData(document, "/api/v1/analysis-records/monthly");
        assertItems(document, monthly, "keywords", "MonthlyAnalysisKeyword", "name", "dreamCount");
        assertItems(
                document, monthly, "emotions", "MonthlyAnalysisEmotion", "emotion", "dreamCount");
        var summary = responseData(document, "/api/v1/worlds/{worldId}/summary");
        assertItems(
                document,
                summary,
                "commonKeywords",
                "WorldSummaryKeyword",
                "keyword",
                "dreamCount");
        assertItems(
                document,
                summary,
                "recurringEmotions",
                "WorldSummaryEmotion",
                "code",
                "label",
                "dreamCount");
        assertThat(
                        resolve(document, monthly.path("properties").path("emotions").path("items"))
                                .at("/properties/emotion/enum")
                                .valueStream()
                                .map(JsonNode::asString)
                                .toList())
                .contains("HAPPY", "CALM");
        assertThat(
                        resolve(
                                        document,
                                        summary.path("properties")
                                                .path("recurringEmotions")
                                                .path("items"))
                                .at("/properties/code/enum")
                                .valueStream()
                                .map(JsonNode::asString)
                                .toList())
                .contains("HAPPY", "CALM");
        var page = responseData(document, "/api/v1/worlds");
        var card = resolve(document, page.at("/properties/items/items"));
        assertThat(card.at("/properties/summary/$ref").asString())
                .isEqualTo("#/components/schemas/WorldSummaryResponse");
        assertThat(resolve(document, card.at("/properties/summary"))).isEqualTo(summary);
    }

    @Test
    void keepsMonthlySummaryAndWorldCardJsonPropertyNamesAndEnvelope() {
        var monthly =
                new AnalysisRecordService.Monthly(
                        YearMonth.of(2026, 10),
                        2,
                        List.of(new AnalysisRecordService.Keyword("바다", 2)),
                        List.of(new AnalysisRecordService.Emotion(DreamEmotion.HAPPY, 2)));
        var content =
                new WorldSummaryContent(
                        "바다의 꿈",
                        List.of(new WorldSummaryContent.Keyword("바다", 2)),
                        List.of(new WorldSummaryContent.Emotion(DreamEmotion.HAPPY, "행복", 2)),
                        2,
                        false);
        var summary =
                new WorldSummaryResponse(
                        9L,
                        1,
                        1L,
                        2,
                        WorldSummaryResponse.Status.COMPLETED,
                        null,
                        content.generatedTitle(),
                        content.commonKeywords(),
                        content.recurringEmotions(),
                        2,
                        false,
                        false,
                        false,
                        false,
                        Instant.parse("2026-10-01T00:00:00Z"),
                        "world-summary-v1");
        var card =
                new WorldFolderCardResponse(
                        9L,
                        "폴더",
                        2,
                        1,
                        LocalDateTime.of(2026, 10, 1, 0, 0),
                        LocalDateTime.of(2026, 10, 1, 0, 0),
                        2,
                        null,
                        summary);
        JsonNode monthlyJson = objectMapper.valueToTree(ApiResponse.success(monthly));
        JsonNode summaryJson = objectMapper.valueToTree(ApiResponse.success(summary));
        JsonNode pageJson =
                objectMapper.valueToTree(
                        ApiResponse.success(new WorldFolderPage(List.of(card), false, null)));
        for (var body : List.of(monthlyJson, summaryJson, pageJson)) {
            assertThat(body.propertyNames())
                    .containsExactlyInAnyOrder("success", "code", "message", "data", "errors");
            assertThat(body.path("success").asBoolean()).isTrue();
            assertThat(body.path("code").asString()).isEqualTo("SUCCESS");
        }
        assertThat(monthlyJson.at("/data/keywords/0").propertyNames())
                .containsExactlyInAnyOrder("name", "dreamCount");
        assertThat(monthlyJson.at("/data/emotions/0").propertyNames())
                .containsExactlyInAnyOrder("emotion", "dreamCount");
        assertThat(monthlyJson.at("/data/emotions/0/emotion").asString()).isEqualTo("HAPPY");
        assertThat(summaryJson.at("/data/commonKeywords/0").propertyNames())
                .containsExactlyInAnyOrder("keyword", "dreamCount");
        assertThat(summaryJson.at("/data/recurringEmotions/0").propertyNames())
                .containsExactlyInAnyOrder("code", "label", "dreamCount");
        assertThat(summaryJson.at("/data/recurringEmotions/0/code").asString()).isEqualTo("HAPPY");
        assertThat(summaryJson.at("/data/recurringEmotions/0/label").asString()).isEqualTo("행복");
        assertThat(summaryJson.at("/data/commonKeywords/0/dreamCount").isIntegralNumber()).isTrue();
        assertThat(pageJson.at("/data/items/0/summary")).isEqualTo(summaryJson.path("data"));
    }

    private JsonNode responseData(JsonNode document, String path) {
        var content =
                document.path("paths")
                        .path(path)
                        .path("get")
                        .path("responses")
                        .path("200")
                        .path("content");
        var media =
                content.has("application/json")
                        ? content.get("application/json")
                        : content.get("*/*");
        assertThat(media).as("GET %s response content", path).isNotNull();
        var envelope = resolve(document, media.path("schema"));
        return resolve(document, envelope.path("properties").path("data"));
    }

    private void assertItems(
            JsonNode document, JsonNode parent, String field, String name, String... properties) {
        var reference = parent.path("properties").path(field).path("items");
        assertThat(reference.path("$ref").asString()).isEqualTo("#/components/schemas/" + name);
        var item = resolve(document, reference);
        assertThat(item.path("properties").propertyNames()).containsExactlyInAnyOrder(properties);
        assertThat(item.at("/properties/dreamCount/type").asString()).isEqualTo("integer");
        assertThat(item.at("/properties/dreamCount/format").asString()).isEqualTo("int64");
    }

    private JsonNode resolve(JsonNode document, JsonNode reference) {
        String path = reference.path("$ref").asString();
        assertThat(path).startsWith("#/components/schemas/");
        var resolved = document.at(path.substring(1));
        assertThat(resolved.isObject()).as(path).isTrue();
        return resolved;
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    @Tag(name = "SetupTest")
    interface ConventionApi {
        @Operation(summary = "문서화 규칙 검증")
        @SecurityRequirement(name = SwaggerConfig.BEARER_AUTH)
        ApiResponse<ConventionResponse> get();
    }

    record ConventionResponse(String value) {}

    // 테스트 클래스패스에만 존재하며 실제 앱에 API를 추가하지 않는다.
    @RestController
    @RequestMapping("/api/v1/setup-test")
    static class ConventionController implements ConventionApi {
        @Override
        @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
        public ApiResponse<ConventionResponse> get() {
            return ApiResponse.success(new ConventionResponse("ok"));
        }
    }
}
