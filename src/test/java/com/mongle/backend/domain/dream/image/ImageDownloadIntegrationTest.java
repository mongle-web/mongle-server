package com.mongle.backend.domain.dream.image;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.auth.service.TokenService;
import com.mongle.backend.domain.user.entity.User;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:mongle-batch-download;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "mongle.credit.enabled=true",
            "mongle.image.styles[0]=test-style",
            "spring.jpa.properties.hibernate.generate_statistics=true",
            "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
        })
@ActiveProfiles("test")
@Import(ImageDownloadContract.Config.class)
class ImageDownloadIntegrationTest extends ImageDownloadContract {
    @Autowired TokenService tokens;
    @Autowired ObjectMapper json;

    @Value("${local.server.port}")
    int port;

    static final String PATH = "/api/v1/images/download-urls";

    @Test
    void httpValidatesRawRequestSizeAndIdsAndRequiresAuthenticatedOnboardedUser() throws Exception {
        String token = tokens.login(user).response().accessToken();
        try (var client = HttpClient.newHttpClient()) {
            assertThat(post(client, "{\"imageIds\":[1]}", null).statusCode()).isEqualTo(401);
            var incomplete = users.saveAndFlush(User.register(UUID.randomUUID() + "@test.invalid"));
            assertThat(
                            post(
                                            client,
                                            "{\"imageIds\":[1]}",
                                            tokens.login(incomplete.getId())
                                                    .response()
                                                    .accessToken())
                                    .statusCode())
                    .isEqualTo(403);
            for (String body :
                    List.of(
                            "{}",
                            "{\"imageIds\":null}",
                            "{\"imageIds\":[]}",
                            "{\"imageIds\":[null]}",
                            "{\"imageIds\":[0]}",
                            "{\"imageIds\":[-1]}",
                            "{\"imageIds\":[1,null]}",
                            json.writeValueAsString(
                                    new ImageDownloadRequest(Collections.nCopies(32, 1L))))) {
                var response = post(client, body, token);
                assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
                assertThat(json.readTree(response.body()).path("code").asString())
                        .isEqualTo("COMMON_400_2");
            }
            assertThat(storage.signed).isEmpty();
            assertThat(
                            post(
                                            client,
                                            json.writeValueAsString(
                                                    new ImageDownloadRequest(
                                                            Collections.nCopies(
                                                                    31, Long.MAX_VALUE))),
                                            token)
                                    .statusCode())
                    .isEqualTo(200);
        }
    }

    @Test
    void httpReturnsOrderedPartialResultsWithUncachedExactFieldsAndKeepsSingleEndpoint()
            throws Exception {
        var a = generated(user);
        var b = generated(user);
        String token = tokens.login(user).response().accessToken();
        storage.response =
                key -> {
                    if (key.equals(key(b.imageId())))
                        throw new IllegalStateException("private-sdk-message");
                    return storage.normal(key);
                };
        try (var client = HttpClient.newHttpClient()) {
            var response =
                    post(
                            client,
                            json.writeValueAsString(
                                    new ImageDownloadRequest(
                                            List.of(
                                                    a.imageId(),
                                                    Long.MAX_VALUE,
                                                    b.imageId(),
                                                    a.imageId()))),
                            token);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
            var body = json.readTree(response.body());
            assertThat(body.propertyNames())
                    .containsExactlyInAnyOrder("success", "code", "message", "data", "errors");
            assertThat(body.path("success").asBoolean()).isTrue();
            var items = body.at("/data/items");
            assertThat(items.size()).isEqualTo(3);
            for (JsonNode item : items)
                assertThat(item.propertyNames())
                        .containsExactlyInAnyOrder(
                                "imageId",
                                "imageVersion",
                                "status",
                                "url",
                                "expiresAt",
                                "failureCode");
            assertThat(items.get(0).path("imageId").asLong()).isEqualTo(a.imageId());
            assertThat(items.get(0).path("imageVersion").asLong()).isEqualTo(a.imageVersion());
            assertThat(items.get(0).path("status").asString()).isEqualTo("SUCCESS");
            assertThat(items.get(1).path("imageVersion").isNull()).isTrue();
            assertThat(items.get(1).path("failureCode").asString()).isEqualTo("IMAGE_404_1");
            assertThat(items.get(2).path("failureCode").asString()).isEqualTo("IMAGE_502_2");
            assertThat(response.body()).doesNotContain("private-sdk-message", "storageKey");
            var single =
                    client.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    "http://localhost:"
                                                            + port
                                                            + "/api/v1/images/"
                                                            + a.imageId()
                                                            + "/download-url"))
                                    .header("Authorization", "Bearer " + token)
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(single.statusCode()).isEqualTo(200);
            assertThat(json.readTree(single.body()).path("data").propertyNames())
                    .containsExactlyInAnyOrder("url", "expiresAt");
        }
    }

    @Test
    void swaggerDocumentsArrayBoundsDedicatedSchemasItemStatusesAndBearerAuth() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response =
                    client.send(
                            HttpRequest.newBuilder(
                                            URI.create("http://localhost:" + port + "/v3/api-docs"))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var spec = json.readTree(response.body());
            var schemas = spec.at("/components/schemas");
            var request = schemas.at("/BatchImageDownloadRequest/properties/imageIds");
            assertThat(request.path("minItems").asInt()).isEqualTo(1);
            assertThat(request.path("maxItems").asInt()).isEqualTo(31);
            assertThat(request.at("/items/format").asString()).isEqualTo("int64");
            assertThat(
                            schemas.at("/BatchImageDownloadResponse/properties/items/items/$ref")
                                    .asString())
                    .isEqualTo("#/components/schemas/BatchImageDownloadItem");
            var item = schemas.path("BatchImageDownloadItem");
            assertThat(item.path("properties").propertyNames())
                    .containsExactlyInAnyOrder(
                            "imageId", "imageVersion", "status", "url", "expiresAt", "failureCode");
            assertThat(item.at("/properties/imageVersion/format").asString()).isEqualTo("int64");
            assertThat(
                            item.at("/properties/status/enum")
                                    .valueStream()
                                    .map(JsonNode::asString)
                                    .toList())
                    .containsExactly("SUCCESS", "FAILED");
            for (String nullable : List.of("imageVersion", "url", "expiresAt", "failureCode")) {
                assertThat(
                                item.path("properties")
                                        .path(nullable)
                                        .path("type")
                                        .valueStream()
                                        .map(JsonNode::asString)
                                        .toList())
                        .contains("null");
            }
            assertThat(item.path("required").valueStream().map(JsonNode::asString).toList())
                    .containsExactlyInAnyOrder(
                            "imageId", "imageVersion", "status", "url", "expiresAt", "failureCode");
            var operation = spec.path("paths").path(PATH).path("post");
            assertThat(operation.path("security").isEmpty()).isFalse();
            assertThat(
                            operation
                                    .at("/requestBody/content/application~1json/schema/$ref")
                                    .asString())
                    .isEqualTo("#/components/schemas/BatchImageDownloadRequest");
            var content = operation.at("/responses/200/content");
            var envelopeRef =
                    (content.has("application/json")
                                    ? content.path("application/json")
                                    : content.path("*/*"))
                            .at("/schema/$ref")
                            .asString();
            assertThat(spec.at(envelopeRef.substring(1)).at("/properties/data/$ref").asString())
                    .isEqualTo("#/components/schemas/BatchImageDownloadResponse");
            assertThat(schemas.at("/MonthlyRecurringElement/properties").propertyNames())
                    .containsExactlyInAnyOrder("name", "dreamCount");
            assertThat(schemas.at("/MonthlyAnalysisKeyword/properties").propertyNames())
                    .containsExactlyInAnyOrder("name", "dreamCount");
            assertThat(schemas.at("/WorldSummaryKeyword/properties").propertyNames())
                    .containsExactlyInAnyOrder("keyword", "dreamCount");
        }
    }

    private HttpResponse<String> post(HttpClient client, String body, String token)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
