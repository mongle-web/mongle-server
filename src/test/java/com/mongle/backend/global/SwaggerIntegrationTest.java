package com.mongle.backend.global;

import com.mongle.backend.global.config.SwaggerConfig;
import com.mongle.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(SwaggerIntegrationTest.ConventionController.class)
class SwaggerIntegrationTest {

    @Value("${local.server.port}") private int port;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void servesOpenApiWithJwtSchemeAndInterfaceAnnotations() throws Exception {
        HttpResponse<String> response = get("/v3/api-docs");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode document = objectMapper.readTree(response.body());
        assertThat(document.at("/info/title").asString()).isEqualTo("몽글 API");
        assertThat(document.at("/components/securitySchemes/bearerAuth/type").asString()).isEqualTo("http");
        assertThat(document.at("/components/securitySchemes/bearerAuth/scheme").asString()).isEqualTo("bearer");
        assertThat(document.at("/components/securitySchemes/bearerAuth/bearerFormat").asString()).isEqualTo("JWT");
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

    private HttpResponse<String> get(String path) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
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
