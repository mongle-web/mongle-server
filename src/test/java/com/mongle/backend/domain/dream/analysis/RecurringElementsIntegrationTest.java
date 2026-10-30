package com.mongle.backend.domain.dream.analysis;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.auth.service.TokenService;
import com.mongle.backend.domain.user.entity.User;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties =
                "spring.datasource.url=jdbc:h2:mem:mongle-recurring;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
@Import(DreamAnalysisIntegrationTest.Config.class)
class RecurringElementsIntegrationTest extends RecurringElementsContract {
    @Value("${local.server.port}")
    int port;

    @Autowired TokenService tokens;
    @Autowired Clock authClock;

    @Test
    void httpRequiresOnboardingReturnsOnlyOwnMonthAndKeepsResponsesUncached() throws Exception {
        analyzed(user, month.atDay(1), List.of("집"), List.of("가족"), 1);
        analyzed(user, month.atDay(2), List.of("집"), List.of("가족"), 1);
        String path = "/api/v1/analysis-records/recurring-elements";
        String token = tokens.login(user).response().accessToken();
        try (var client = HttpClient.newHttpClient()) {
            assertThat(get(client, path, null).statusCode()).isEqualTo(401);
            var incomplete = users.saveAndFlush(User.register(UUID.randomUUID() + "@test.invalid"));
            assertThat(
                            get(
                                            client,
                                            path,
                                            tokens.login(incomplete.getId())
                                                    .response()
                                                    .accessToken())
                                    .statusCode())
                    .isEqualTo(403);
            var response = get(client, path + "?month=" + month, token);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(response.headers().firstValue("cache-control")).hasValue("no-store");
            var body = json.readTree(response.body());
            assertThat(body.propertyNames())
                    .containsExactlyInAnyOrder("success", "code", "message", "data", "errors");
            var data = body.path("data");
            assertThat(data.propertyNames())
                    .containsExactlyInAnyOrder(
                            "month", "analyzedDreamCount", "places", "characters");
            assertThat(data.path("month").asString()).isEqualTo(month.toString());
            assertThat(data.path("analyzedDreamCount").asLong()).isEqualTo(2);
            assertThat(data.at("/places/0/name").asString()).isEqualTo("집");
            assertThat(data.at("/characters/0/dreamCount").asLong()).isEqualTo(2);
            assertThat(data.at("/places/0").propertyNames())
                    .containsExactlyInAnyOrder("name", "dreamCount");
            var other =
                    users.saveAndFlush(User.create(UUID.randomUUID() + "@test.invalid", "다른회원"));
            var empty =
                    json.readTree(
                                    get(
                                                    client,
                                                    path + "?month=" + month,
                                                    tokens.login(other.getId())
                                                            .response()
                                                            .accessToken())
                                            .body())
                            .path("data");
            assertThat(empty.path("analyzedDreamCount").asLong()).isZero();
            assertThat(empty.path("places").isEmpty()).isTrue();
            assertThat(empty.path("characters").isEmpty()).isTrue();
            assertThat(json.readTree(get(client, path, token).body()).at("/data/month").asString())
                    .isEqualTo(
                            YearMonth.now(authClock.withZone(ZoneId.of("Asia/Seoul"))).toString());
            for (String invalid :
                    List.of("", "2026-00", "2026-13", "2026-1", "2026-01-01", "oops")) {
                var result = get(client, path + "?month=" + invalid, token);
                assertThat(result.statusCode()).as(result.body()).isEqualTo(400);
                assertThat(
                                get(
                                                client,
                                                "/api/v1/analysis-records/monthly?month=" + invalid,
                                                token)
                                        .statusCode())
                        .isEqualTo(result.statusCode());
            }
        }
    }

    @Test
    void openApiUsesDedicatedSchemasAndMaintainsExistingMonthlyAndWorldSummarySchemas()
            throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = get(client, "/v3/api-docs", null);
            assertThat(response.statusCode()).isEqualTo(200);
            var document = json.readTree(response.body());
            var schemas = document.at("/components/schemas");
            var recurring = schemas.path("MonthlyRecurringElementsResponse");
            assertThat(recurring.path("properties").propertyNames())
                    .containsExactlyInAnyOrder(
                            "month", "analyzedDreamCount", "places", "characters");
            for (String field : List.of("places", "characters")) {
                assertThat(recurring.path("properties").path(field).at("/items/$ref").asString())
                        .isEqualTo("#/components/schemas/MonthlyRecurringElement");
            }
            var item = schemas.path("MonthlyRecurringElement");
            assertThat(item.path("properties").propertyNames())
                    .containsExactlyInAnyOrder("name", "dreamCount");
            assertThat(item.at("/properties/dreamCount/format").asString()).isEqualTo("int64");
            assertThat(schemas.path("MonthlyAnalysisKeyword").path("properties").propertyNames())
                    .containsExactlyInAnyOrder("name", "dreamCount");
            assertThat(schemas.path("WorldSummaryKeyword").path("properties").propertyNames())
                    .containsExactlyInAnyOrder("keyword", "dreamCount");
            var operation =
                    document.path("paths")
                            .path("/api/v1/analysis-records/recurring-elements")
                            .path("get");
            assertThat(operation.path("security").isEmpty()).isFalse();
            var content = operation.at("/responses/200/content");
            var schema =
                    (content.has("application/json")
                                    ? content.path("application/json")
                                    : content.path("*/*"))
                            .path("schema");
            var envelope = document.at(schema.path("$ref").asString().substring(1));
            assertThat(envelope.at("/properties/data/$ref").asString())
                    .isEqualTo("#/components/schemas/MonthlyRecurringElementsResponse");
        }
    }

    private HttpResponse<String> get(HttpClient client, String path, String token)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(10));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
