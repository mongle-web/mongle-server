package com.mongle.backend.domain.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OAuthSecurityIntegrationTest {
    @Value("${local.server.port}") private int port;

    @Test
    void protectsBusinessEndpointsWithJsonAndKeepsSwaggerPublicWithoutProviderKeys() throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var protectedResponse = get(client, "/api/v1/users/me");
            assertThat(protectedResponse.statusCode()).isEqualTo(401);
            var json = JsonMapper.builder().build().readTree(protectedResponse.body());
            assertThat(json.get("code").asString()).isEqualTo("COMMON_UNAUTHORIZED");
            assertThat(json.get("success").asBoolean()).isFalse();
            assertThat(protectedResponse.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(get(client, "/swagger-ui/index.html").statusCode()).isEqualTo(200);
            assertThat(get(client, "/v3/api-docs").statusCode()).isEqualTo(200);
        }
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
