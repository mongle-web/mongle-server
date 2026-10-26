package com.mongle.backend.domain.auth;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "KAKAO_CLIENT_ID=test-kakao",
            "KAKAO_CLIENT_SECRET=test-secret",
            "MONGLE_OAUTH_GOOGLE_ENABLED=false",
            "spring.datasource.url=jdbc:h2:mem:kakao-only;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
        })
@ActiveProfiles({"test", "oauth"})
class KakaoOnlyOAuthIntegrationTest {
    @Value("${local.server.port}")
    int port;

    @Autowired ClientRegistrationRepository registrations;

    @Test
    void startsKakaoWithoutGoogleKeysAndBlocksDisabledOrUnknownProviders() throws Exception {
        assertThat(registrations.findByRegistrationId("google")).isNull();
        try (var client = HttpClient.newHttpClient()) {
            var kakao = get(client, "/oauth2/authorization/kakao");
            assertThat(kakao.statusCode()).isEqualTo(302);
            assertThat(kakao.headers().firstValue("Location").orElseThrow())
                    .startsWith("https://kauth.kakao.com/oauth/authorize?")
                    .contains("account_email");
            for (String path :
                    new String[] {
                        "/oauth2/authorization/google",
                        "/oauth2/authorization/unknown",
                        "/login/oauth2/code/google"
                    }) {
                var blocked = get(client, path);
                assertThat(blocked.statusCode()).isEqualTo(400);
                assertThat(blocked.body()).contains("AUTH_400_1");
                assertThat(blocked.headers().firstValue("Location")).isEmpty();
            }
        }
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
